// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.extensions;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extended from kotlin-compiler-1.3.72 stub: adds getExtensions(ExtensionPointName),
 * called by CodeStyleSettings (openapi-formatter.jar) which was compiled against
 * a full IntelliJ Extensions class that included this method.
 * The other three methods match the original kotlin-compiler implementation exactly.
 */
public final class Extensions {
  private static ExtensionsAreaImpl ourRootArea;

  private Extensions() {}

  public static void setRootArea(@NotNull ExtensionsAreaImpl area, @NotNull Disposable parentDisposable) {
    ExtensionsAreaImpl oldArea = ourRootArea;
    ourRootArea = area;
    Disposer.register(parentDisposable, () -> {
      ourRootArea.notifyAreaReplaced(oldArea);
      ourRootArea = oldArea;
    });
  }

  @NotNull
  public static ExtensionsArea getRootArea() {
    return ourRootArea;
  }

  @NotNull
  public static ExtensionsArea getArea(@Nullable AreaInstance areaInstance) {
    if (areaInstance == null) {
      return ourRootArea;
    }
    return areaInstance.getExtensionArea();
  }

  /** Added: missing from kotlin-compiler-1.3.72 stub, called by openapi-formatter */
  @NotNull
  public static <T> T[] getExtensions(@NotNull ExtensionPointName<T> extensionPointName) {
    return getRootArea().getExtensionPoint(extensionPointName).getExtensions();
  }
}
