/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package javaproject.mockservices

import java.beans.PropertyChangeListener
import org.netbeans.api.project.Project
import org.netbeans.api.project.ProjectUtils
import org.netbeans.api.project.ui.ProjectGroup
import org.netbeans.api.project.ui.ProjectGroupChangeListener
import org.netbeans.modules.project.uiapi.OpenProjectsTrampoline

class MockOpenProjectsTrampoline : OpenProjectsTrampoline {
    
    private val openProjects = arrayListOf<Project>()
    private var mainProject: Project? = null
    
    override fun getOpenProjectsAPI() = openProjects.toTypedArray()
        
    override fun openAPI(projects: Array<Project>, openRequiredProjects: Boolean, bool: Boolean) {
        openProjects.addAll(projects)
        if (projects.size > 0) {
            mainProject = projects.last()
        }
    }

    override fun closeAPI(projects: Array<Project>) {
        openProjects.removeAll(projects)
    }

    override fun addPropertyChangeListenerAPI(listener: PropertyChangeListener?, source: Any?) {
    }

    override fun removePropertyChangeListenerAPI(listener: PropertyChangeListener?) {
    }

    override fun getMainProject() = mainProject
    
    override fun setMainProject(project: Project?) {
        if (project != null && !openProjects.contains(project)) {
            throw IllegalArgumentException("Project ${ProjectUtils.getInformation(project).displayName} is not open and cannot be set as main.")
        }
            mainProject = project
        }

    override fun openProjectsAPI() = null
    

    override fun getActiveProjectGroupAPI() = null

    override fun addProjectGroupChangeListenerAPI(pl: ProjectGroupChangeListener?) {}

    override fun removeProjectGroupChangeListenerAPI(pl: ProjectGroupChangeListener?) {}

    override fun createLogicalView() = null

    override fun createPhysicalView() = null
}