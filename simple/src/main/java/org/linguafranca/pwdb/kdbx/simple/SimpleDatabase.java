/*
 * Copyright 2015 Jo Rabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.linguafranca.pwdb.kdbx.simple;

import org.linguafranca.pwdb.*;
import org.linguafranca.pwdb.base.AbstractDatabase;
import org.linguafranca.pwdb.kdbx.*;
import org.linguafranca.pwdb.kdbx.simple.converter.*;
import org.linguafranca.pwdb.kdbx.simple.model.EntryClasses;
import org.linguafranca.pwdb.kdbx.simple.model.KeePassFile;
import org.linguafranca.security.Credentials;
import org.linguafranca.xml.XmlInputStreamFilter;
import org.linguafranca.xml.XmlOutputStreamFilter;
import org.simpleframework.xml.*;
import org.simpleframework.xml.convert.AnnotationStrategy;
import org.simpleframework.xml.convert.Convert;
import org.simpleframework.xml.convert.Registry;
import org.simpleframework.xml.convert.RegistryStrategy;
import org.simpleframework.xml.core.Persister;
import org.simpleframework.xml.strategy.Strategy;
import org.simpleframework.xml.util.Dictionary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * @author jo
 */
@SuppressWarnings("WeakerAccess")
public class SimpleDatabase extends AbstractDatabase{

    private KeePassFile keePassFile;

    public SimpleDatabase() {
        try {
            keePassFile = createEmptyDatabase();
            keePassFile.root.group.database = this;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected SimpleDatabase (KeePassFile keePassFile) {
        this.keePassFile = keePassFile;
        this.keePassFile.root.group.database = this;
        fixUp(this.keePassFile.root.group);
    }

    private static Serializer getSerializer() throws Exception {
        Registry registry = new Registry();
        registry.bind(String.class, EmptyStringConverter.class);
        Strategy strategy = new AnnotationStrategy(new RegistryStrategy(registry));
        return new Persister(strategy);

    }

    @Override
    public org.linguafranca.pwdb.Group getRootGroup() {
        return keePassFile.root.getGroup();
    }

    @Override
    public org.linguafranca.pwdb.Group newGroup() {
        return SimpleGroup.createGroup(this, null);
    }

    @Override
    public org.linguafranca.pwdb.Entry newEntry() {
        return SimpleEntry.createEntry(this, null);
    }

    @Override
    public Icon newIcon() {
        return new SimpleIcon();
    }

    @Override
    public Icon newIcon(Integer integer) {
        Icon ic = newIcon();
        ic.setIndex(integer);
        return ic;
    }

    @Override
    public String getName() {
        return keePassFile.meta.databaseName;
    }

    @Override
    public void setName(String s) {
        keePassFile.meta.databaseName = s;
        keePassFile.meta.databaseNameChanged = new Date();
        setDirty(true);
    }

    @Override
    public String getDescription() {
        return keePassFile.meta.databaseDescription;
    }

    @Override
    public void setDescription(String s) {
        keePassFile.meta.databaseDescription = s;
        keePassFile.meta.databaseDescriptionChanged = new Date();
        setDirty(true);
    }

    private static KeePassFile createEmptyDatabase() throws Exception {
        InputStream inputStream = SimpleDatabase.class.getClassLoader().getResourceAsStream("base.kdbx.xml");
        KeePassFile result = getSerializer().read(KeePassFile.class, inputStream);
        return result;
    }

    public static SimpleDatabase loadXml(InputStream inputStream) throws Exception {
        KeePassFile result =  getSerializer().read(KeePassFile.class, inputStream);
        return new SimpleDatabase(result);
    }

    public static SimpleDatabase load(Credentials credentials, InputStream inputStream) throws Exception {

        // load the KDBX header and get the inner Kdbx stream
        KdbxHeader kdbxHeader = new KdbxHeader();
        InputStream kdbxInnerStream = KdbxSerializer.createUnencryptedInputStream(credentials, kdbxHeader, inputStream);

        // decrypt the encrypted fields in the inner XML stream
        InputStream plainTextXmlStream = new XmlInputStreamFilter(kdbxInnerStream,
                new KdbxInputTransformer(new Salsa20StreamEncryptor(kdbxHeader.getProtectedStreamKey())));

        // read the now entirely decrypted stream into database
        KeePassFile result = getSerializer().read(KeePassFile.class, plainTextXmlStream);
        if (!Arrays.equals(result.meta.headerHash.getContent(), kdbxHeader.getHeaderHash())) {
            throw new IllegalStateException("Header Hash Mismatch");
        }

        return new SimpleDatabase(result);
    }

    public void save(OutputStream outputStream) {
        try {
            prepareForSave(keePassFile.root.group);

            // and save the database out
            getSerializer().write(this.keePassFile, outputStream);

        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

    }

    @Override
    public void save(Credentials credentials, OutputStream outputStream) throws IOException {
        try {
            // create the stream to accept unencrypted data and output to encrypted
            KdbxHeader kdbxHeader = new KdbxHeader();
            OutputStream kdbxInnerStream = KdbxSerializer.createEncryptedOutputStream(credentials, kdbxHeader, outputStream);

            // the database contains the hash of the headers
            keePassFile.meta.headerHash.setContent(kdbxHeader.getHeaderHash());

            // encrypt the fields in the XML inner stream
            XmlOutputStreamFilter plainTextOutputStream = new XmlOutputStreamFilter(kdbxInnerStream,
                    new KdbxOutputTransformer(new Salsa20StreamEncryptor(kdbxHeader.getProtectedStreamKey())));

            // set up the "protected" attributes of fields that need inner stream encryption
            prepareForSave(keePassFile.root.group);

            // and save the database out
            getSerializer().write(this.keePassFile, plainTextOutputStream);
            plainTextOutputStream.close();
            this.setDirty(false);

        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fixUp(SimpleGroup parent){
        for (SimpleGroup group: parent.group) {
            group.parent = parent;
            group.database = parent.database;
            fixUp(group);
        }
        for (SimpleEntry entry: parent.entry) {
            entry.database = parent.database;
            entry.parent = parent;
        }
    }

    private static void prepareForSave(SimpleGroup parent){
        for (SimpleGroup group: parent.group) {
            prepareForSave(group);
        }
        for (SimpleEntry entry: parent.entry) {
            for (EntryClasses.StringProperty property : entry.string) {
                boolean shouldProtect = parent.database.shouldProtect(property.getKey());
                property.getValue().setProtected(shouldProtect);
            }
        }
    }

    @Override
    public boolean shouldProtect(String s) {
        return keePassFile.meta.memoryProtection.shouldProtect(s);
    }


    public List<KeePassFile.Binaries.Binary> getBinaries() {
        return keePassFile.getBinaries();
    }
}