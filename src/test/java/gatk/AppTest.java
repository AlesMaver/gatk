/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package gatk;

import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.contrib.nio.CloudStorageConfiguration;
import com.google.cloud.storage.contrib.nio.CloudStorageFileSystemProvider;
import com.google.common.base.Strings;
import org.testng.Assert;
import org.testng.annotations.*;
import shaded.cloud_nio.com.google.api.gax.retrying.RetrySettings;
import shaded.cloud_nio.org.threeten.bp.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class AppTest {
    static {
        setGlobalNIODefaultOptions(5, "");
    }

    private static Path getRemotePath(){
        try {
            return Paths.get(new URI("gs://hellbender/test/resources/large/exampleLargeFile.txt"));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testExists() throws URISyntaxException, IOException {
        Assert.assertTrue(Files.exists(getRemotePath()));
    }

    @Test
    public void testIsRegularFile() throws URISyntaxException, IOException {
        Assert.assertTrue(Files.isRegularFile(getRemotePath()));
    }

    @Test
    public void testRead() throws URISyntaxException, IOException {
        try(InputStream inputStream = Files.newInputStream(getRemotePath())){
            Assert.assertNotEquals(inputStream.read(), - 1);
        }
    }

    @Test
    public void testPseudoDirectory() throws URISyntaxException {
        Assert.assertTrue(Files.isDirectory(Paths.get(new URI("gs://hellbender/test/resources/large/"))));
    }

    @Test
    public void testNonExistant() throws URISyntaxException {
        Assert.assertTrue(Files.isDirectory(Paths.get(new URI("gs://hellbender/hargle/blargle/"))));
    }
    

    @Test
    public void testCopy() throws URISyntaxException, IOException {
        final Path target = Paths.get("local.txt");
        final Path remotePath = getRemotePath();
        Files.copy(remotePath, target);
        Assert.assertEquals(Files.size(target), Files.size(remotePath));
        Files.delete(target);
    }

    public static void setGlobalNIODefaultOptions(int maxReopens, String requesterProject) {
        CloudStorageFileSystemProvider.setDefaultCloudStorageConfiguration(getCloudStorageConfiguration(maxReopens, requesterProject));
        CloudStorageFileSystemProvider.setStorageOptions(setGenerousTimeouts(StorageOptions.newBuilder()).build());
    }

    public static CloudStorageConfiguration getCloudStorageConfiguration(int maxReopens, String requesterProject) {
        CloudStorageConfiguration.Builder builder = CloudStorageConfiguration.builder()
                // if the channel errors out, re-open up to this many times
                .maxChannelReopens(maxReopens);
        if (!Strings.isNullOrEmpty(requesterProject)) {
            // enable requester pays and indicate who pays
            builder = builder.autoDetectRequesterPays(true).userProject(requesterProject);
        }

        //this causes the gcs filesystem to treat files that end in a / as a directory
        //true is the default but this protects against future changes in behavior
        builder.usePseudoDirectories(true);
        return builder.build();
    }

    private static StorageOptions.Builder setGenerousTimeouts(StorageOptions.Builder builder) {
        return builder
                .setTransportOptions(HttpTransportOptions.newBuilder()
                        .setConnectTimeout(120000)
                        .setReadTimeout(120000)
                        .build())
                .setRetrySettings(RetrySettings.newBuilder()
                        .setMaxAttempts(15)
                        .setMaxRetryDelay(Duration.ofMillis(256_000L))
                        .setTotalTimeout(Duration.ofMillis(4000_000L))
                        .setInitialRetryDelay(Duration.ofMillis(1000L))
                        .setRetryDelayMultiplier(2.0)
                        .setInitialRpcTimeout(Duration.ofMillis(180_000L))
                        .setRpcTimeoutMultiplier(1.0)
                        .setMaxRpcTimeout(Duration.ofMillis(180_000L))
                        .build());
    }
}
