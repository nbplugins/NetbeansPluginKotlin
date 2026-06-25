package com.intellij.openapi.module;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.Collections;
public abstract class ModuleManager {
    public static ModuleManager getInstance(Project project) { return null; }
    public abstract Module[] getModules();
    public List<UnloadedModuleDescription> getUnloadedModuleDescriptions() { return Collections.emptyList(); }
}
