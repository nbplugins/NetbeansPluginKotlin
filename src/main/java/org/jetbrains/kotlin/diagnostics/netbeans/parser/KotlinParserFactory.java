package org.jetbrains.kotlin.diagnostics.netbeans.parser;

import java.util.Collection;
import org.jetbrains.kotlin.log.KotlinLogger;
import org.netbeans.modules.parsing.api.Snapshot;
import org.netbeans.modules.parsing.spi.Parser;
import org.netbeans.modules.parsing.spi.ParserFactory;

public class KotlinParserFactory extends ParserFactory {

    @Override
    public Parser createParser(Collection<Snapshot> snapshots) {
        KotlinLogger.INSTANCE.logInfo("KotlinParserFactory.createParser called");
        return new KotlinParser();
    }
}
