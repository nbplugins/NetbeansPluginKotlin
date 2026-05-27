package javaproject.mockservices

import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParser
import org.netbeans.modules.parsing.api.Snapshot
import org.netbeans.modules.parsing.spi.Parser
import org.netbeans.modules.parsing.spi.ParserFactory


class MockKotlinParserFactory : ParserFactory() {
    override fun createParser(snapshots: Collection<Snapshot>?) = KotlinParser()
}