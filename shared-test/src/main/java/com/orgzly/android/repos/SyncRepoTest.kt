package com.orgzly.android.repos

import android.annotation.SuppressLint
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.orgzly.android.BookName
import com.orgzly.android.util.MiscUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.IOException

@SuppressLint("NewApi")
interface SyncRepoTest {

    fun testGetBooks_singleOrgFile()
    fun testGetBooks_singleFileInSubfolder()
    fun testGetBooks_allFilesAreIgnored()
    fun testGetBooks_specificFileInSubfolderIsIgnored()
    fun testGetBooks_specificFileIsUnignored()
    fun testGetBooks_ignoredExtensions()
    fun testStoreBook_expectedUri()
    fun testStoreBook_producesSameUriAsRetrieveBook()
    fun testStoreBook_producesSameUriAsGetBooks()
    fun testStoreBook_inSubfolder()
    fun testRenameBook_expectedUri()
    fun testRenameBook_repoFileAlreadyExists()
    fun testRenameBook_fromRootToSubfolder()
    fun testRenameBook_fromSubfolderToRoot()
    fun testRenameBook_newSubfolderSameLeafName()
    fun testRenameBook_newSubfolderAndLeafName()
    fun testRenameBook_sameSubfolderNewLeafName()

    companion object {

        const val repoDirName = "orgzly-android-test"
        private var treeDocumentFileExtraSegment = if (Build.VERSION.SDK_INT < 30) {
            "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F$repoDirName%2F"
        } else {
            "/document/primary%3A$repoDirName%2F"
        }

        fun testGetBooks_singleOrgFile(syncRepo: SyncRepo) {
            // Given
            val fileContent = "\n\n...\n\n"
            val fileName = "Book one.org"
            val expectedRookUri = syncRepo.writeFileToRepo(fileContent, fileName)

            // When
            val retrieveBookDestinationFile = kotlin.io.path.createTempFile().toFile()
            syncRepo.retrieveBook(fileName, retrieveBookDestinationFile)
            val books = syncRepo.books

            // Then
            assertEquals(1, books.size)
            assertEquals(expectedRookUri, books[0].uri.toString())
            assertEquals(fileContent, retrieveBookDestinationFile.readText())
            assertEquals(fileName, BookName.getRepoRelativePath(syncRepo.uri, books[0].uri))
        }

        fun testGetBooks_singleFileInSubfolder(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val repoFilePath = "Folder/Book one.org"
            val fileContent = "\n\n...\n\n"
            val expectedRookUri = syncRepo.writeFileToRepo(fileContent, "Folder/Book one.org")

            // When
            val retrieveBookDestinationFile = kotlin.io.path.createTempFile().toFile()
            syncRepo.retrieveBook(repoFilePath, retrieveBookDestinationFile)
            val books = syncRepo.books

            // Then
            assertEquals(1, books.size)
            assertEquals(expectedRookUri, books[0].uri.toString())
            assertEquals(repoFilePath, BookName.getRepoRelativePath(syncRepo.uri, books[0].uri))
            assertEquals(fileContent, retrieveBookDestinationFile.readText())
        }

        fun testGetBooks_allFilesAreIgnored(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val ignoreFileContent = "*\n"
            syncRepo.writeFileToRepo("...", "folder/book one.org")
            syncRepo.writeFileToRepo(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE)
            // When
            val books = syncRepo.books
            // Then
            assertEquals(0, books.size)
        }

        fun testGetBooks_specificFileInSubfolderIsIgnored(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val ignoreFileContent = "folder/book one.org\n"
            syncRepo.writeFileToRepo("...", "folder/book one.org")
            syncRepo.writeFileToRepo(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE)
            // When
            val books = syncRepo.books
            // Then
            assertEquals(0, books.size)
        }
        fun testGetBooks_specificFileIsUnignored(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val folderName = "My Folder"
            val fileName = "My file.org"
            val ignoreFileContent = "folder/**\n!$folderName/$fileName\n"
            syncRepo.writeFileToRepo("...", "$folderName/$fileName")
            syncRepo.writeFileToRepo(ignoreFileContent, RepoIgnoreNode.IGNORE_FILE)
            // When
            val books = syncRepo.books
            // Then
            assertEquals(1, books.size)
        }

        fun testGetBooks_ignoredExtensions(syncRepo: SyncRepo) {
            // Given
            val testBookContent = "\n\n...\n\n"
            for (fileName in arrayOf("file one.txt", "file two.o", "file three.org")) {
                syncRepo.writeFileToRepo(testBookContent, fileName)
            }
            // When
            val books = syncRepo.books
            // Then
            assertEquals(1, books.size.toLong())
            assertEquals("file three", BookName.fromRepoRelativePath(BookName.getRepoRelativePath(syncRepo.uri, books[0].uri)).name)
        }

        fun testStoreBook_expectedUri(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val vrook = syncRepo.storeBook(tmpFile, "Book one.org")
            tmpFile.delete()
            // Then
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "Book%20one.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Book%20one.org"
                else -> syncRepo.uri.toString() + "/Book%20one.org"
            }
            assertEquals(expectedRookUri, vrook.uri.toString())
        }

        fun testStoreBook_producesSameUriAsRetrieveBook(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val repositoryPath = "a folder/a book.org"
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val storedRook = syncRepo.storeBook(tmpFile, repositoryPath)
            val retrievedBook = syncRepo.retrieveBook(repositoryPath, tmpFile)
            tmpFile.delete()
            // Then
            assertEquals(retrievedBook.uri, storedRook.uri)
        }

        fun testStoreBook_producesSameUriAsGetBooks(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val folderName = "A folder"
            val fileName = "A book.org"
            syncRepo.writeFileToRepo("...", "$folderName/$fileName")
            // When
            val gottenBook = syncRepo.books[0]
            MiscUtils.writeStringToFile("......", tmpFile) // N.B. Different content to ensure the repo file is actually changed
            val storedRook = syncRepo.storeBook(tmpFile, "$folderName/$fileName")
            tmpFile.delete()
            // Then
            assertEquals(gottenBook.uri, storedRook.uri)
        }

        fun testStoreBook_inSubfolder(repoManipulationPoint: Any, syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val repositoryPath = "A folder/A book.org"
            val testBookContent = "\n\n...\n\n"
            MiscUtils.writeStringToFile(testBookContent, tmpFile)
            // When
            syncRepo.storeBook(tmpFile, repositoryPath)
            tmpFile.delete()
            // Then
            when (syncRepo) {
                is WebdavRepo -> {
                    repoManipulationPoint as File
                    val subFolder = File(repoManipulationPoint, "A folder")
                    assertTrue(subFolder.exists())
                    val bookFile = File(subFolder, "A book.org")
                    assertTrue(bookFile.exists())
                    assertEquals(testBookContent, bookFile.readText())
                }
                is GitRepo -> {
                    repoManipulationPoint as File
                    val git = Git(
                        FileRepositoryBuilder()
                            .addCeilingDirectory(repoManipulationPoint)
                            .findGitDir(repoManipulationPoint)
                            .build()
                    )
                    git.pull().call()
                    val subFolder = File(repoManipulationPoint, "A folder")
                    assertTrue(subFolder.exists())
                    val bookFile = File(subFolder, "A book.org")
                    assertTrue(bookFile.exists())
                    assertEquals(testBookContent, bookFile.readText())
                }
                is DocumentRepo -> {
                    repoManipulationPoint as DocumentFile
                    val subFolder = repoManipulationPoint.findFile("A folder")
                    assertTrue(subFolder!!.exists())
                    assertTrue(subFolder.isDirectory)
                    val bookFile = subFolder.findFile("A book.org")
                    assertTrue(bookFile!!.exists())
                    assertEquals(testBookContent, MiscUtils.readStringFromDocumentFile(bookFile))
                }
                is DropboxRepo -> {
                    // Not really much to assert here; we don't really care how Dropbox implements things,
                    // as long as URLs work as expected.
                    repoManipulationPoint as DropboxClient
                    val retrievedFile = kotlin.io.path.createTempFile().toFile()
                    repoManipulationPoint.download(syncRepo.uri, repositoryPath, retrievedFile)
                    assertEquals(testBookContent, retrievedFile.readText())
                }
            }
        }

        fun testRenameBook_expectedUri(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            val oldFileName = "Original book.org"
            val newBookName = "Renamed book"
            val testBookContent = "\n\n...\n\n"
            MiscUtils.writeStringToFile(testBookContent, tmpFile)
            // When
            val originalVrook = syncRepo.storeBook(tmpFile, oldFileName)
            tmpFile.delete()
            syncRepo.renameBook(originalVrook.uri, newBookName)
            // Then
            val renamedVrook = syncRepo.books[0]
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "Renamed%20book.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Renamed%20book.org"
                else -> syncRepo.uri.toString() + "/Renamed%20book.org"
            }
            assertEquals(expectedRookUri, renamedVrook.uri.toString())
        }

        fun testRenameBook_repoFileAlreadyExists(syncRepo: SyncRepo) {
            // Given
            for (fileName in arrayOf("Original.org", "Renamed.org")) {
                syncRepo.writeFileToRepo("...", fileName)
            }
            val retrievedBookFile = kotlin.io.path.createTempFile().toFile()
            // When
            val originalRook = syncRepo.retrieveBook("Original.org", retrievedBookFile)
            try {
                syncRepo.renameBook(originalRook.uri, "Renamed")
            } catch (e: IOException) {
                // Then
                assertTrue(e.message!!.contains("Renamed.org already exists"))
                throw e
            } finally {
                retrievedBookFile.delete()
            }
        }

        fun testRenameBook_fromRootToSubfolder(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val originalRook = syncRepo.storeBook(tmpFile, "Original book.org")
            tmpFile.delete()
            val renamedRook = syncRepo.renameBook(originalRook.uri, "A folder/Renamed book")
            // Then
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "A%20folder/Renamed%20book.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "A%20folder%2FRenamed%20book.org"
                else -> syncRepo.uri.toString() + "/A%20folder/Renamed%20book.org"
            }
            assertEquals(expectedRookUri, renamedRook.uri.toString())
        }

        fun testRenameBook_fromSubfolderToRoot(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val originalRook = syncRepo.storeBook(tmpFile, "A folder/Original book.org")
            tmpFile.delete()
            val renamedRook = syncRepo.renameBook(originalRook.uri, "Renamed book")
            // Then
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "Renamed%20book.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "Renamed%20book.org"
                else -> syncRepo.uri.toString() + "/Renamed%20book.org"
            }
            assertEquals(expectedRookUri, renamedRook.uri.toString())
        }

        fun testRenameBook_newSubfolderSameLeafName(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val originalRook = syncRepo.storeBook(tmpFile, "Old folder/Original book.org")
            tmpFile.delete()
            val renamedRook = syncRepo.renameBook(originalRook.uri, "New folder/Original book")
            // Then
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "New%20folder/Original%20book.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "New%20folder%2FOriginal%20book.org"
                else -> syncRepo.uri.toString() + "/New%20folder/Original%20book.org"
            }
            assertEquals(expectedRookUri, renamedRook.uri.toString())
        }

        fun testRenameBook_newSubfolderAndLeafName(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val originalRook = syncRepo.storeBook(tmpFile, "old folder/Original book.org")
            tmpFile.delete()
            val renamedRook = syncRepo.renameBook(originalRook.uri, "new folder/New book")
            // Then
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "new%20folder/New%20book.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "new%20folder%2FNew%20book.org"
                else -> syncRepo.uri.toString() + "/new%20folder/New%20book.org"
            }
            assertEquals(expectedRookUri, renamedRook.uri.toString())
        }

        fun testRenameBook_sameSubfolderNewLeafName(syncRepo: SyncRepo) {
            // Given
            val tmpFile = kotlin.io.path.createTempFile().toFile()
            MiscUtils.writeStringToFile("...", tmpFile)
            // When
            val originalRook = syncRepo.storeBook(tmpFile, "old folder/Original book.org")
            tmpFile.delete()
            val renamedRook = syncRepo.renameBook(originalRook.uri, "old folder/New book")
            // Then
            val expectedRookUri = when (syncRepo) {
                is GitRepo -> "old%20folder/New%20book.org"
                is DocumentRepo -> syncRepo.uri.toString() + treeDocumentFileExtraSegment + "old%20folder%2FNew%20book.org"
                else -> syncRepo.uri.toString() + "/old%20folder/New%20book.org"
            }
            assertEquals(expectedRookUri, renamedRook.uri.toString())
        }

/*
        */
/**
         * @return The rook's expected URI
         *//*

        private fun writeFileToRepo(
            content: String,
            repo: SyncRepo,
            repoManipulationPoint: Any,
            fileName: String,
            folderName: String? = null
        ): String {
            var expectedRookUri = repo.uri.toString() + "/" + Uri.encode(fileName)
            when (repo) {
                is WebdavRepo -> {
                    var targetDir = repoManipulationPoint as File
                    if (folderName != null) {
                        targetDir = File(targetDir.absolutePath + "/$folderName")
                        targetDir.mkdir()
                        expectedRookUri = repo.uri.toString() + "/" + Uri.encode("$folderName/$fileName", "/")
                    }
                    val remoteBookFile = File(targetDir.absolutePath + "/$fileName")
                    MiscUtils.writeStringToFile(content, remoteBookFile)
                }
                is GitRepo -> {
                    expectedRookUri = Uri.encode("$fileName", "/")
                    var targetDir = repoManipulationPoint as File
                    if (folderName != null) {
                        expectedRookUri = Uri.encode("$folderName/$fileName", "/")
                        targetDir = File(targetDir.absolutePath + "/$folderName")
                        targetDir.mkdir()
                    }
                    MiscUtils.writeStringToFile(
                        content,
                        File(targetDir.absolutePath + "/$fileName")
                    )
                    updateGitRepo(repoManipulationPoint)
                }
                is DocumentRepo -> {
                    expectedRookUri = repo.uri.toString() + treeDocumentFileExtraSegment + Uri.encode(fileName)
                    var targetDir = repoManipulationPoint as DocumentFile
                    if (folderName != null) {
                        targetDir = targetDir.createDirectory(folderName)!!
                        expectedRookUri = repo.uri.toString() + treeDocumentFileExtraSegment + Uri.encode("$folderName/$fileName")
                    }
                    MiscUtils.writeStringToDocumentFile(content, fileName, targetDir.uri)
                }
                is DropboxRepo -> {
                    repoManipulationPoint as DropboxClient
                    val tmpFile = kotlin.io.path.createTempFile().toFile()
                    MiscUtils.writeStringToFile(content, tmpFile)
                    var targetPath = fileName
                    if (folderName != null) {
                        targetPath = "$folderName/$fileName"
                        expectedRookUri = repo.uri.toString() + "/" + Uri.encode("$folderName/$fileName", "/")
                    }
                    repoManipulationPoint.upload(tmpFile, repo.uri, targetPath)
                    tmpFile.delete()
                }
            }
            return expectedRookUri
        }
*/

        private fun updateGitRepo(workdir: File) {
            val git = Git(
                FileRepositoryBuilder()
                    .addCeilingDirectory(workdir)
                    .findGitDir(workdir)
                    .build()
            )
            git.add().addFilepattern(".").call()
            git.commit().setMessage("").call()
            git.push().call()
        }
    }
}
