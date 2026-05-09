package com.intellij.openapi.components;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

/**
 * Stub replacing core:193's ServiceManager. The original delegates to
 * Project.getService(Class, boolean) which was removed in 232+; the API on the loaded
 * (232-era) Project is just getService(Class). This stub adapts callers (code-style:241,
 * Nbm Kotlin code) to the new single-arg shape.
 */
public final class ServiceManager {
    private ServiceManager() {}

    public static <T> T getService(Class<T> serviceClass) {
        Application app = ApplicationManager.getApplication();
        return app == null ? null : app.getService(serviceClass);
    }

    public static <T> T getService(Project project, Class<T> serviceClass) {
        return project == null ? null : project.getService(serviceClass);
    }

    public static <T> T getServiceIfCreated(Class<T> serviceClass) {
        Application app = ApplicationManager.getApplication();
        return app == null ? null : app.getServiceIfCreated(serviceClass);
    }

    public static <T> T getServiceIfCreated(Project project, Class<T> serviceClass) {
        return project == null ? null : project.getServiceIfCreated(serviceClass);
    }
}
