-- readRecord schema shipped in 3.26102103 (database version 112).
DROP TABLE readRecord;
CREATE TABLE IF NOT EXISTS `readRecord` (`deviceId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `author` TEXT NOT NULL DEFAULT '', `readTime` INTEGER NOT NULL DEFAULT 0, `lastRead` INTEGER NOT NULL DEFAULT 0, `lastChapterTitle` TEXT, `lastChapterIndex` INTEGER NOT NULL DEFAULT -1, `lastChapterPos` INTEGER NOT NULL DEFAULT 0, `coverUrl` TEXT, `resolvedAuthor` TEXT, `displayBookName` TEXT NOT NULL DEFAULT '', `displayAuthor` TEXT NOT NULL DEFAULT '', `metadataEditedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`deviceId`, `bookName`, `author`));
CREATE INDEX IF NOT EXISTS `index_readRecord_snapshot` ON `readRecord` (`displayBookName` ASC, `displayAuthor` ASC, `lastRead` DESC, `deviceId` ASC, `bookName` ASC, `author` ASC);
CREATE INDEX IF NOT EXISTS `index_readRecord_bookName_author` ON `readRecord` (`bookName`, `author`);
UPDATE room_master_table SET identity_hash = 'c14623b88e01ed7a319e831c16046297' WHERE id = 42;
