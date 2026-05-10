package com.intellij.openapi.vfs;

import java.text.MessageFormat;

/** Stub: VfsBundle was removed from core:232; only message() is called by BuiltInsReferenceResolver. */
public class VfsBundle {
    public static String message(String key, Object... params) {
        return params.length == 0 ? key : MessageFormat.format(key, params);
    }
}
