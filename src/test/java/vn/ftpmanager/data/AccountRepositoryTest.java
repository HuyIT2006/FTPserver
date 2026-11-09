package vn.ftpmanager.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesExistingAccountsAndKeepsWritingToVisibleDatabase() throws Exception {
        Path legacyDatabase = temporaryDirectory.resolve("legacy").resolve("ftp-manager.db");
        Files.createDirectories(legacyDatabase.getParent());
        Path existingHome = temporaryDirectory.resolve("legacy-users").resolve("old_user");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacyDatabase);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ftp_accounts (
                        username TEXT PRIMARY KEY COLLATE NOCASE,
                        password_salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        home_directory TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("INSERT INTO ftp_accounts(username, password_salt, password_hash, home_directory) " +
                    "VALUES ('old_user', 'salt', 'hash', '" + existingHome + "')");
        }

        Path visibleDirectory = temporaryDirectory.resolve("ftp-manager-data");
        Path storageDirectory = temporaryDirectory.resolve("ftp-users");
        AccountRepository repository = new AccountRepository(visibleDirectory, legacyDatabase, storageDirectory);

        assertEquals(1, repository.count());
        assertEquals(existingHome, repository.find("old_user").homeDirectory());

        repository.create("new_user", "StrongPass123".toCharArray());
        assertTrue(Files.isRegularFile(visibleDirectory.resolve("ftp-manager.db")));
        assertEquals(2, repository.count());
    }
}