package com.starhub;

import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.arjPropertyManager;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import com.mysql.cj.jdbc.MysqlDataSource;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/** Optional replicated decision log. Cross-host coordinator fencing is still required. */
final class JdbcDecisionStore {
    private static final String PREFIX="starhub_", TABLE=PREFIX+"JBossTSTxTable";
    private static String configuredFingerprint;
    private JdbcDecisionStore() { }
    static DataSource from(Properties properties,String expectedIdentity)throws SQLException {
        String backend=properties.getProperty("storage.objectStore.backend","file");
        if("file".equals(backend))return null;
        if(!"jdbc".equals(backend))throw new IllegalArgumentException("Unknown decision-store backend");
        required(properties,"storage.objectStore.jdbc.url");String url=AccountServlet.jdbcUrl(properties,"storage.objectStore.jdbc.url");
        if(!url.matches("jdbc:mysql://(?:127[.]0[.]0[.]1|localhost):[0-9]+/[A-Za-z0-9_]+[?].*"))
            throw new IllegalArgumentException("Decision store must use a private loopback MySQL endpoint");
        boolean replicated=ReplicaAuthority.enabled(properties);
        if(replicated)ReplicaAuthority.validateEndpoints(properties);
        MysqlDataSource source=replicated?new GuardedDataSource(properties):new MysqlDataSource();source.setURL(url);
        source.setUser(required(properties,"storage.objectStore.jdbc.user"));
        source.setPassword(required(properties,"storage.objectStore.jdbc.password"));
        source.setConnectTimeout(3000);source.setSocketTimeout(AccountServlet.databaseSocketTimeout(properties));
        validate(source,expectedIdentity);return source;
    }

    private static final class GuardedDataSource extends MysqlDataSource {
        private final Properties config=new Properties();
        GuardedDataSource(Properties properties){config.putAll(properties);}
        @Override public Connection getConnection(String user,String password)throws SQLException {
            return ReplicaAuthority.checked(super.getConnection(user,password),config);
        }
    }
    private static String required(Properties p,String key) {
        String value=p.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;
    }
    private static void validate(DataSource source,String expectedIdentity)throws SQLException {
        try(Connection c=source.getConnection()) {
            try(PreparedStatement q=c.prepareStatement("SELECT STORE_UUID FROM starhub_decision_store_identity WHERE ID=1");ResultSet r=q.executeQuery()) {
                if(expectedIdentity==null||!r.next()||!expectedIdentity.equals(r.getString(1)))throw new SQLException("Decision store identity is missing or mismatched");
            }
            try(PreparedStatement q=c.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                q.setString(1,TABLE);try(ResultSet r=q.executeQuery()) {
                    if(!r.next()||!"InnoDB".equalsIgnoreCase(r.getString(1)))throw new SQLException("Decision table is missing or is not InnoDB");
                }
            }
            try(PreparedStatement q=c.prepareStatement("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY'")) {
                q.setString(1,TABLE);try(ResultSet r=q.executeQuery()) {
                    if(!r.next()||!"UidString,TypeName,StateType".equalsIgnoreCase(r.getString(1)))throw new SQLException("Decision table has an unexpected primary key");
                }
            }
            try(PreparedStatement q=c.prepareStatement("SELECT StateType,Hidden,TypeName,UidString,ObjectState FROM `"+TABLE+"` WHERE 1=0")){q.executeQuery().close();}
        }
    }
    static void requireEmptyLegacyStore(Path store)throws IOException {
        try(var entries=Files.walk(store)) {
            boolean unknown=entries.anyMatch(path->{
                if(path.equals(store))return false;
                if(Files.isSymbolicLink(path))return true;
                if(Files.isDirectory(path))return false;
                return !path.equals(store.resolve("identity"))&&!path.equals(store.resolve("coordinator.lock"));
            });
            if(unknown)throw new IOException("Legacy transaction-store files remain; recover them before changing backend");
        }
    }
    static String fingerprint(DataSource source,String identifier,Path store) {
        if(!(source instanceof MysqlDataSource mysql))throw new IllegalArgumentException("Expected MySQL decision source");
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{mysql.getURL(),mysql.getUser(),mysql.getPassword(),identifier,store.toAbsolutePath().normalize().toString()}) {
                byte[] bytes=java.util.Objects.requireNonNull(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static synchronized void configure(DataSource source,String identifier,Path store) {
        String fingerprint=fingerprint(source,identifier,store);
        if(configuredFingerprint!=null&&!configuredFingerprint.equals(fingerprint))
            throw new IllegalStateException("Decision store configuration changed; restart the application");
        var bean=arjPropertyManager.getObjectStoreEnvironmentBean();
        bean.setObjectStoreType("com.arjuna.ats.internal.arjuna.objectstore.jdbc.JDBCStore");
        bean.setTablePrefix(PREFIX);bean.setCreateTable(false);bean.setDropTable(false);
        for(String name:new String[]{null,"stateStore","communicationStore"})
            BeanPopulator.getNamedInstance(ObjectStoreEnvironmentBean.class,name).setJdbcDataSource(source);
        configuredFingerprint=fingerprint;
    }
}
