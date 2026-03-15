package nl.alowaniak.runelite.musicreplacer;

import org.slf4j.LoggerFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

public class FileUtil {

    public static void copyFileAsync(File source, File destination) {
        CompletableFuture.runAsync(() -> {
            try {
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LoggerFactory.getLogger(FileUtil.class).error("Error copying file asynchronously", e);
            }
        });
    }
}
