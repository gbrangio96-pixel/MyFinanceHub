package br.com.myfinancehub;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** The sharing grant is deliberately narrower than the app's private financial storage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class ReportShareProviderTest {
    private Context context;
    private ReportShareProvider provider;
    private File directory;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        directory = new File(context.getCacheDir().getCanonicalFile(), "reports");
        clearReports();
        ProviderInfo info = new ProviderInfo();
        info.authority = context.getPackageName() + ".reports";
        info.exported = false;
        info.grantUriPermissions = true;
        provider = new ReportShareProvider();
        provider.attachInfo(context, info);
    }

    @After public void after() throws Exception { clearReports(); }

    @Test public void eachExportGetsItsOwnPrivatePdfAndDescriptiveMetadata() throws Exception {
        File first = pdf("%PDF-1.4\nexample");
        File second = ReportShareProvider.newPdfFile(context);
        assertNotEquals(first, second);
        assertEquals(directory, first.getParentFile());
        assertTrue(first.getName().matches("[0-9a-f-]{36}\\.pdf"));
        Uri uri = ReportShareProvider.uriFor(context, first);
        assertEquals("content", uri.getScheme());
        assertEquals(context.getPackageName() + ".reports", uri.getAuthority());
        assertEquals("application/pdf", provider.getType(uri));
        try (Cursor cursor = provider.query(uri, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals("MyFinanceHub-relatorio.pdf",
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(first.length(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
        }
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                provider.openFile(uri, "r"))) {
            assertArrayEquals("%PDF-1.4\nexample".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
        }
    }

    @Test public void requestedProjectionOrderAndUnknownColumnsAreRespected() throws Exception {
        File file = pdf("%PDF-projection");
        try (Cursor cursor = provider.query(ReportShareProvider.uriFor(context, file),
                new String[]{OpenableColumns.SIZE, "unknown", OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(file.length(), cursor.getLong(0));
            assertTrue(cursor.isNull(1));
            assertEquals("MyFinanceHub-relatorio.pdf", cursor.getString(2));
        }
    }

    @Test public void noWriteOrTruncateModeCanModifyAnExport() throws Exception {
        File file = pdf("%PDF-read-only");
        Uri uri = ReportShareProvider.uriFor(context, file);
        for (String mode : new String[]{"w", "wt", "wa", "rw", "rwt", "", null}) {
            try {
                provider.openFile(uri, mode);
                fail("Write mode must be rejected: " + mode);
            } catch (FileNotFoundException expected) {
                assertTrue(expected.getMessage().contains("leitura"));
            }
        }
        assertEquals("%PDF-read-only", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void arbitraryPrivateFilesAndFakePdfNamesCannotBeShared() throws Exception {
        File database = new File(context.getCacheDir(), "myfinancehub.db");
        Files.write(database.toPath(), "private data".getBytes(StandardCharsets.UTF_8));
        try {
            expectFileFailure(() -> ReportShareProvider.uriFor(context, database));
            File outsidePdf = new File(context.getCacheDir(), "00000000-0000-0000-0000-000000000000.pdf");
            Files.write(outsidePdf.toPath(), "private data".getBytes(StandardCharsets.UTF_8));
            try { expectFileFailure(() -> ReportShareProvider.uriFor(context, outsidePdf)); }
            finally { assertTrue(outsidePdf.delete()); }
            assertTrue(directory.mkdirs() || directory.isDirectory());
            File namedPdf = new File(directory, "myfinancehub.db.pdf");
            Files.write(namedPdf.toPath(), "private data".getBytes(StandardCharsets.UTF_8));
            expectFileFailure(() -> ReportShareProvider.uriFor(context, namedPdf));
        } finally { assertTrue(database.delete()); }
    }

    @Test public void malformedOrEncodedPathsNeverResolveOutsideTheReportsFolder() throws Exception {
        Uri valid = ReportShareProvider.uriFor(context, pdf("%PDF-scope"));
        String base = "content://" + valid.getAuthority();
        String name = valid.getLastPathSegment();
        for (String candidate : new String[]{base, base + "/", base + "/../myfinancehub.db",
                base + "/%2e%2e/myfinancehub.db", base + "/%2e%2e%2fmyfinancehub.db",
                base + "/nested/" + name, base + "//" + name,
                base + "/" + name + "?download=1", base + "/" + name + "#fragment",
                "file://" + directory + "/" + name, "content://other.reports/" + name,
                base + "/" + name.replace(".pdf", "%2epdf")}) {
            Uri uri = Uri.parse(candidate);
            expectUriFailure(() -> provider.query(uri, null, null, null, null));
            expectUriFailure(() -> provider.getType(uri));
            try { provider.openFile(uri, "r"); fail("Must reject " + candidate); }
            catch (FileNotFoundException expected) { /* No descriptor escapes. */ }
        }
    }

    @Test public void missingExportFailsInsteadOfReturningMetadataForAnotherFile() throws Exception {
        File file = pdf("%PDF-expired");
        Uri uri = ReportShareProvider.uriFor(context, file);
        assertTrue(file.delete());
        expectFileFailure(() -> ReportShareProvider.uriFor(context, file));
        expectUriFailure(() -> provider.query(uri, null, null, null, null));
        expectUriFailure(() -> provider.getType(uri));
        try { provider.openFile(uri, "r"); fail("Missing file cannot be opened"); }
        catch (FileNotFoundException expected) { /* Cache was cleared. */ }
    }

    @Test public void aDirectoryWithAPdfNameIsNotAnExport() throws Exception {
        File file = ReportShareProvider.newPdfFile(context);
        Uri uri = ReportShareProvider.uriFor(context, file);
        assertTrue(file.delete());
        assertTrue(file.mkdir());
        expectFileFailure(() -> ReportShareProvider.uriFor(context, file));
        expectUriFailure(() -> provider.query(uri, null, null, null, null));
        assertTrue(file.delete());
    }

    @Test public void symlinkCannotExposeOtherApplicationFiles() throws Exception {
        File file = pdf("%PDF-placeholder");
        Uri uri = ReportShareProvider.uriFor(context, file);
        assertTrue(file.delete());
        File secret = new File(context.getCacheDir(), "not-a-report.txt");
        Files.write(secret.toPath(), "private data".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(file.toPath(), secret.toPath());
            expectFileFailure(() -> ReportShareProvider.uriFor(context, file));
            expectUriFailure(() -> provider.query(uri, null, null, null, null));
            try { provider.openFile(uri, "r"); fail("Symlink cannot be shared"); }
            catch (FileNotFoundException expected) { /* Canonical scope is enforced. */ }
        } finally {
            Files.deleteIfExists(file.toPath());
            assertTrue(secret.delete());
        }
    }

    @Test public void cleanupExpiresOnlyOldProviderFilesAndPreservesRecentGrants() throws Exception {
        File expired = pdf("%PDF-old");
        File recent = pdf("%PDF-recent");
        File other = new File(directory, "keep-this.txt");
        Files.write(other.toPath(), "unrelated cache".getBytes(StandardCharsets.UTF_8));
        long old = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25);
        assertTrue(expired.setLastModified(old));
        assertTrue(other.setLastModified(old));
        File fresh = ReportShareProvider.newPdfFile(context);
        assertFalse(expired.exists());
        assertTrue(recent.exists());
        assertTrue(other.exists());
        assertTrue(fresh.exists());
        assertEquals("application/pdf", provider.getType(ReportShareProvider.uriFor(context, recent)));
    }

    @Test public void providerNeverOffersMutationOperations() {
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".reports/anything.pdf");
        expectReadOnly(() -> provider.insert(uri, new ContentValues()));
        expectReadOnly(() -> provider.update(uri, new ContentValues(), null, null));
        expectReadOnly(() -> provider.delete(uri, null, null));
    }

    private File pdf(String content) throws IOException {
        File file = ReportShareProvider.newPdfFile(context);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private void clearReports() throws IOException {
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(directory.toPath());
    }

    private interface Operation { Object run() throws Exception; }
    private static void expectFileFailure(Operation operation) throws Exception {
        try { operation.run(); fail("Only generated PDFs may be shared"); }
        catch (IOException expected) { /* Invalid/missing/scope-breaking file. */ }
    }
    private static void expectUriFailure(Operation operation) throws Exception {
        try { operation.run(); fail("Only canonical generated PDF URIs may be read"); }
        catch (IllegalArgumentException expected) { /* Invalid URI or unavailable cache. */ }
    }
    private static void expectReadOnly(Operation operation) {
        try { operation.run(); fail("Provider must be read only"); }
        catch (UnsupportedOperationException expected) { /* No mutation endpoint. */ }
        catch (Exception unexpected) { throw new AssertionError(unexpected); }
    }
}
