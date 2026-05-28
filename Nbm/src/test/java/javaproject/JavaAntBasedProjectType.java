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
package javaproject;

import java.io.IOException;
import org.netbeans.api.project.Project;
import org.netbeans.spi.project.support.ant.AntBasedProjectType;
import org.netbeans.spi.project.support.ant.AntProjectHelper;

/**
 *
 * @author Alexander.Baratynski
 */
@org.openide.util.lookup.ServiceProvider(service = AntBasedProjectType.class, position = 1)
public class JavaAntBasedProjectType implements AntBasedProjectType{
    
    public static final String TYPE = "org.netbeans.modules.java.j2seproject";
    static final String PROJECT_CONFIGURATION_NAME = "data";
    public static final String PROJECT_CONFIGURATION_NAMESPACE = "http://www.netbeans.org/ns/j2se-project/3";
    static final String PRIVATE_CONFIGURATION_NAME = "data";
    static final String PRIVATE_CONFIGURATION_NAMESPACE = "http://www.netbeans.org/ns/j2se-project-private/1";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Project createProject(AntProjectHelper helper) throws IOException {
        return new org.netbeans.modules.java.j2seproject.J2SEProject(helper);
    }

    @Override
    public String getPrimaryConfigurationDataElementName(boolean shared) {
        return shared ? PROJECT_CONFIGURATION_NAME : PRIVATE_CONFIGURATION_NAME;
    }

    @Override
    public String getPrimaryConfigurationDataElementNamespace(boolean shared) {
        return shared ? PROJECT_CONFIGURATION_NAMESPACE : PRIVATE_CONFIGURATION_NAMESPACE;
    }
    
}
