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

import javax.swing.event.ChangeListener
import org.netbeans.modules.parsing.api.Snapshot
import org.netbeans.modules.parsing.api.Task
import org.netbeans.modules.parsing.spi.ParseException
import org.netbeans.modules.parsing.spi.Parser
import org.netbeans.modules.parsing.spi.SourceModificationEvent

class MockParser : Parser() {
    override fun parse(snapshot: Snapshot?, task: Task?, event: SourceModificationEvent?) {}

    override fun getResult(task: Task?) = object : Result(null) {
        override fun invalidate() {}
    }
    
    override fun addChangeListener(changeListener: ChangeListener?) {
    }

    override fun removeChangeListener(changeListener: ChangeListener?) {
    }
}