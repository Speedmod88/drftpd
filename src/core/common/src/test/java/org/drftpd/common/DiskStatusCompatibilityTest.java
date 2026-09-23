package org.drftpd.common;

import org.drftpd.common.slave.DiskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiskStatusCompatibilityTest {
    @TempDir
    Path temporary;

    @Test
    void diskStatusCanBeExchangedWithLegacyPeersInBothDirections() throws Exception {
        Path source = temporary.resolve("DiskStatus.java");
        Files.writeString(source, "package org.drftpd.common.slave; "
                + "public class DiskStatus implements java.io.Serializable {"
                + "private static final long serialVersionUID = 3573098662042584609L;"
                + "private final long _free; private final long _total;"
                + "public DiskStatus(long free, long total) { _free=free; _total=total; }"
                + "public long getBytesAvailable() { return _free; }"
                + "public long getBytesCapacity() { return _total; }}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", temporary.toString(), source.toString()));
        try (URLClassLoader legacyLoader = new URLClassLoader(new URL[]{temporary.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> legacyClass = legacyLoader.loadClass(DiskStatus.class.getName());
            Object legacy = legacyClass.getConstructor(long.class, long.class).newInstance(100L, 1000L);
            DiskStatus restored = (DiskStatus) deserialize(serialize(legacy), DiskStatus.class.getClassLoader());
            assertEquals(100L, restored.getBytesAvailable());
            assertFalse(restored.hasMinimumFreeSpaceStatus());
            assertFalse(restored.isBelowMinimumFreeSpace());

            DiskStatus updated = new DiskStatus(200L, 2000L, true, "root.1 free=200B min=300B");
            Object oldReceiver = deserialize(serialize(updated), legacyLoader);
            assertEquals(200L, legacyClass.getMethod("getBytesAvailable").invoke(oldReceiver));
            assertEquals(2000L, legacyClass.getMethod("getBytesCapacity").invoke(oldReceiver));

            DiskStatus newReceiver = (DiskStatus) deserialize(serialize(updated), DiskStatus.class.getClassLoader());
            assertTrue(newReceiver.isBelowMinimumFreeSpace());
            assertEquals(updated.getMinimumFreeSpaceDetails(), newReceiver.getMinimumFreeSpaceDetails());
        }
    }

    private byte[] serialize(Object object) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(object);
        }
        return bytes.toByteArray();
    }

    private Object deserialize(byte[] bytes, ClassLoader loader) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass descriptor) throws ClassNotFoundException {
                return Class.forName(descriptor.getName(), false, loader);
            }
        }) {
            return input.readObject();
        }
    }
}
