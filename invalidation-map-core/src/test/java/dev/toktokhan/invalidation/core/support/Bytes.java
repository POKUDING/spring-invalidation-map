package dev.toktokhan.invalidation.core.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** 테스트 클래스패스에서 컴파일된 클래스 파일의 바이트를 읽습니다. */
public final class Bytes {

    private Bytes() {
    }

    public static byte[] of(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("클래스 파일을 찾을 수 없습니다: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
