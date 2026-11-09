package vn.ftpmanager.data;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

public final class AccountRepository {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path databasePath;
    private final Path storagePath;

    public AccountRepository(Path applicationDirectory) throws SQLException, java.io.IOException {
        this(applicationDirectory, null, applicationDirectory.resolve("shared"));
    }

    public AccountRepository(Path applicationDirectory, Path legacyDatabasePath)
            throws SQLException, java.io.IOException {
        this(applicationDirectory, legacyDatabasePath, applicationDirectory.resolve("shared"));
    }

    public AccountRepository(Path applicationDirectory, Path legacyDatabasePath, Path storagePath)
            throws SQLException, java.io.IOException {
        Files.createDirectories(applicationDirectory);
        databasePath = applicationDirectory.resolve("ftp-manager.db");
        boolean databaseAlreadyExisted = Files.exists(databasePath);
        this.storagePath = storagePath;
        Files.createDirectories(storagePath);
        initialize();
        if (!databaseAlreadyExisted) {
            migrateLegacyDatabase(legacyDatabasePath);
        } else {
            migrateExistingAccountHomes();
        }
    }

    public Path getSharedStoragePath() {
        return storagePath;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private void initialize() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ftp_accounts (
                        username TEXT PRIMARY KEY COLLATE NOCASE,
                        password_salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        home_directory TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    private void migrateLegacyDatabase(Path legacyDatabasePath) throws SQLException, java.io.IOException {
        if (legacyDatabasePath == null || legacyDatabasePath.equals(databasePath)
                || !Files.isRegularFile(legacyDatabasePath)) {
            return;
        }
        try (Connection legacy = DriverManager.getConnection("jdbc:sqlite:" + legacyDatabasePath);
             var tableCheck = legacy.createStatement();
             ResultSet table = tableCheck.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'ftp_accounts'")) {
            if (!table.next()) {
                return;
            }
            List<LegacyAccount> accounts = new ArrayList<>();
            try (PreparedStatement readAccounts = legacy.prepareStatement(
                    "SELECT username, password_salt, password_hash, home_directory, enabled, created_at " +
                            "FROM ftp_accounts");
                 ResultSet results = readAccounts.executeQuery()) {
                while (results.next()) {
                    accounts.add(new LegacyAccount(results.getString("username"),
                            results.getString("password_salt"), results.getString("password_hash"),
                            Path.of(results.getString("home_directory")), results.getInt("enabled"),
                            results.getString("created_at")));
                }
            }
            for (LegacyAccount account : accounts) {
                copyLegacyFiles(account.username(), account.homeDirectory());
            }
            try (Connection target = connect();
                 PreparedStatement insertAccount = target.prepareStatement(
                         "INSERT INTO ftp_accounts(username, password_salt, password_hash, home_directory, enabled, created_at) " +
                                 "VALUES (?, ?, ?, ?, ?, ?)")) {
                target.setAutoCommit(false);
                try {
                    for (LegacyAccount account : accounts) {
                        insertAccount.setString(1, account.username());
                        insertAccount.setString(2, account.passwordSalt());
                        insertAccount.setString(3, account.passwordHash());
                        insertAccount.setString(4, storagePath.toString());
                        insertAccount.setInt(5, account.enabled());
                        insertAccount.setString(6, account.createdAt());
                        insertAccount.addBatch();
                    }
                    insertAccount.executeBatch();
                    target.commit();
                } catch (SQLException exception) {
                    target.rollback();
                    throw exception;
                }
            }
        }
    }

    private void migrateExistingAccountHomes() throws SQLException, java.io.IOException {
        List<FtpAccount> accounts = findAll();
        for (FtpAccount account : accounts) {
            Path previousHome = account.homeDirectory().toAbsolutePath().normalize();
            if (!previousHome.equals(storagePath.toAbsolutePath().normalize())) {
                copyLegacyFiles(account.username(), previousHome);
                try (Connection connection = connect();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE ftp_accounts SET home_directory = ? WHERE username = ?")) {
                    statement.setString(1, storagePath.toString());
                    statement.setString(2, account.username());
                    statement.executeUpdate();
                }
            }
        }
    }

    private void copyLegacyFiles(String username, Path sourceDirectory) throws java.io.IOException {
        if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path destinationRoot = storagePath.resolve(username).normalize();
        if (!destinationRoot.startsWith(storagePath) || sourceDirectory.equals(destinationRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            for (Path source : paths.toList()) {
                if (source.equals(sourceDirectory) || Files.isSymbolicLink(source)) {
                    continue;
                }
                Path destination = destinationRoot.resolve(sourceDirectory.relativize(source)).normalize();
                if (!destination.startsWith(destinationRoot)) {
                    continue;
                }
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination.getParent());
                    if (!Files.exists(destination)) {
                        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        }
    }

    public List<FtpAccount> findAll() throws SQLException {
        List<FtpAccount> accounts = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT username, home_directory, enabled FROM ftp_accounts ORDER BY username");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                accounts.add(new FtpAccount(results.getString("username"),
                        Path.of(results.getString("home_directory")), results.getInt("enabled") == 1));
            }
        }
        return accounts;
    }

    public int count() throws SQLException {
        try (Connection connection = connect();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ftp_accounts")) {
            return result.getInt(1);
        }
    }

    public FtpAccount find(String username) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT username, home_directory, enabled FROM ftp_accounts WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new FtpAccount(result.getString("username"),
                        Path.of(result.getString("home_directory")), result.getInt("enabled") == 1);
            }
        }
    }

    public boolean authenticate(String username, String password) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT password_salt, password_hash, enabled FROM ftp_accounts WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt("enabled") != 1) {
                    return false;
                }
                byte[] salt = Base64.getDecoder().decode(result.getString("password_salt"));
                byte[] expected = Base64.getDecoder().decode(result.getString("password_hash"));
                return MessageDigest.isEqual(expected, hash(password.toCharArray(), salt));
            }
        }
    }

    public void create(String username, char[] password) throws Exception {
        String normalized = username.trim();
        if (!normalized.matches("[a-zA-Z0-9._-]{3,32}")) {
            throw new IllegalArgumentException("Tên tài khoản cần dài 3-32 ký tự, chỉ gồm chữ, số, ., _ hoặc -.");
        }
        if (password.length < 8) {
            throw new IllegalArgumentException("Mật khẩu cần có ít nhất 8 ký tự.");
        }

        Files.createDirectories(storagePath);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO ftp_accounts(username, password_salt, password_hash, home_directory) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, normalized);
            statement.setString(2, Base64.getEncoder().encodeToString(salt));
            statement.setString(3, Base64.getEncoder().encodeToString(hash(password, salt)));
            statement.setString(4, storagePath.toString());
            statement.executeUpdate();
        }
    }

    public void delete(String username) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM ftp_accounts WHERE username = ?")) {
            statement.setString(1, username);
            statement.executeUpdate();
        }
    }

    private static byte[] hash(char[] password, byte[] salt) {
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash password", exception);
        } finally {
            keySpec.clearPassword();
        }
    }

    private record LegacyAccount(String username, String passwordSalt, String passwordHash,
                                 Path homeDirectory, int enabled, String createdAt) {
    }
}