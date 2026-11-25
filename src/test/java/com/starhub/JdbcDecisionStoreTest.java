package com.starhub;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

final class JdbcDecisionStoreTest {
    @Test void defaultStoreIsUnchanged() throws Exception {
        assertNull(JdbcDecisionStore.from(new Properties(),null));
    }
    @Test void rejectsInvalidConfiguration() {
        Properties p=new Properties();p.setProperty("storage.objectStore.backend","jdbc");
        assertThrows(IllegalArgumentException.class,()->JdbcDecisionStore.from(p,null));
        p.setProperty("storage.objectStore.jdbc.user","test");p.setProperty("storage.objectStore.jdbc.password","test");
        p.setProperty("storage.objectStore.jdbc.url","jdbc:mysql://example.com:3306/starhub?sslMode=VERIFY_IDENTITY");
        assertThrows(IllegalArgumentException.class,()->JdbcDecisionStore.from(p,null));
        p.setProperty("storage.objectStore.backend","unexpected");
        assertThrows(IllegalArgumentException.class,()->JdbcDecisionStore.from(p,null));
    }
    @Test void refusesToDiscardOldDecisionFiles() throws Exception {
        var path=Files.createTempDirectory("starhub-old-store-");
        Files.writeString(path.resolve("identity"),"marker");Files.writeString(path.resolve("coordinator.lock"),"");
        JdbcDecisionStore.requireEmptyLegacyStore(path);
        var dir=Files.createDirectories(path.resolve("ShadowNoFileLockStore/defaultStore/AtomicAction"));
        Files.writeString(dir.resolve("unresolved"),"decision");
        assertThrows(java.io.IOException.class,()->JdbcDecisionStore.requireEmptyLegacyStore(path));
    }
    @Test void identifiesEveryPartOfTheDecisionStoreConfiguration() {
        var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setURL("jdbc:mysql://127.0.0.1:3306/test");source.setUser("user");source.setPassword("password");
        var path=java.nio.file.Path.of("/tmp/starhub-store");
        String original=JdbcDecisionStore.fingerprint(source,"node",path);
        assertEquals(original,JdbcDecisionStore.fingerprint(source,"node",path));
        assertNotEquals(original,JdbcDecisionStore.fingerprint(source,"other-node",path));
        assertNotEquals(original,JdbcDecisionStore.fingerprint(source,"node",java.nio.file.Path.of("/tmp/other-store")));
        source.setPassword("different");assertNotEquals(original,JdbcDecisionStore.fingerprint(source,"node",path));
    }
}
