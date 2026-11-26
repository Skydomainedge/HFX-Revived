package top.weixiansen574.hybridfilexfer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import top.weixiansen574.hybridfilexfer.pc.engine.adb.AdbManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PcShellCliTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testLegacyArgumentsRemainFullyCompatible() throws Exception {
        CliOptions adbOpts = CliOptions.parse(new String[]{"-c", "adb", "-s", "3B15B100P8E00000", "-d", "D:\\Transfer"});
        assertEquals("adb", adbOpts.connect);
        assertEquals("3B15B100P8E00000", adbOpts.device);
        assertEquals("D:\\Transfer", adbOpts.homeDir);
        assertFalse(adbOpts.adbLegacy);
        assertNull(adbOpts.adbPath);
        assertNull(adbOpts.adbStreams);

        CliOptions ipOpts = CliOptions.parse(new String[]{"--connect=10.94.81.247", "--dir=/tmp"});
        assertEquals("10.94.81.247", ipOpts.connect);
        assertEquals("/tmp", ipOpts.homeDir);

        CliOptions versionOpts = CliOptions.parse(new String[]{"-v"});
        assertTrue(versionOpts.showVersion);

        CliOptions helpOpts = CliOptions.parse(new String[]{"--help"});
        assertTrue(helpOpts.showHelp);

        CliOptions emptyOpts = CliOptions.parse(new String[0]);
        assertTrue(emptyOpts.emptyArgs);
    }

    @Test
    public void testM2AdbTurboOptionsAndConfigMapping() throws Exception {
        CliOptions opts = CliOptions.parse(new String[]{
                "-c", "adb",
                "--adb-path", "C:\\Tools\\platform-tools\\adb.exe",
                "--adb-legacy",
                "--adb-streams=4",
                "--no-auto-bind"
        });
        assertEquals("adb", opts.connect);
        assertEquals("C:\\Tools\\platform-tools\\adb.exe", opts.adbPath);
        assertTrue(opts.adbLegacy);
        assertEquals(Integer.valueOf(4), opts.adbStreams);
        assertTrue(opts.disableAutoBind);

        AdbManager.AdbConfig adbConfig = Main.buildAdbConfig(opts);
        assertEquals("C:\\Tools\\platform-tools\\adb.exe", adbConfig.explicitAdbPath);
        assertTrue(adbConfig.adbLegacy);
        assertEquals(Integer.valueOf(4), adbConfig.overrideStreams);
    }

    @Test
    public void testInvalidOptionsRejected() {
        try {
            CliOptions.parse(new String[]{"--unknown-flag"});
            fail("Expected ParseException for unknown option");
        } catch (CliOptions.ParseException expected) {
            assertEquals("unknown_option", expected.messageKey);
        }

        try {
            CliOptions.parse(new String[]{"-c", "adb", "--adb-streams", "0"});
            fail("Expected ParseException for out-of-range --adb-streams");
        } catch (CliOptions.ParseException expected) {
            assertEquals("invalid_argument", expected.messageKey);
        }

        try {
            CliOptions.parse(new String[]{"-c", "adb", "--adb-streams=9"});
            fail("Expected ParseException for --adb-streams > 8");
        } catch (CliOptions.ParseException expected) {
            assertEquals("invalid_argument", expected.messageKey);
        }
    }

    @Test
    public void testResolveEffectiveHomeDirCreatesMissingAndFallsBackOnFile() throws Exception {
        assertEquals("/", Main.resolveEffectiveHomeDir("/"));
        assertEquals("/", Main.resolveEffectiveHomeDir(""));

        File missingDir = new File(tempFolder.getRoot(), "auto_created_bench_dir");
        assertFalse(missingDir.exists());
        String resolved = Main.resolveEffectiveHomeDir(missingDir.getAbsolutePath());
        assertTrue(missingDir.isDirectory());
        assertEquals(missingDir.getAbsolutePath(), resolved);

        File regularFile = tempFolder.newFile("not_a_dir.txt");
        assertEquals("/", Main.resolveEffectiveHomeDir(regularFile.getAbsolutePath()));
    }
}
