package io.kanon.specctl.core.rule;

import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.ir.RuleAst;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuleParserTest {
    @Test
    void parsesTypedRuleExpressions() {
        RuleParser parser = new RuleParser();
        RuleAst.ParsedRule parsed = parser.parse(new SpecDocument.Rule(
                "SA-002",
                SpecDocument.RuleType.VALIDATION,
                "duration(shiftStart, shiftEnd) <= PT12H",
                SpecDocument.Severity.ERROR
        ));

        assertThat(parsed.expression()).isInstanceOf(RuleAst.Binary.class);
        RuleAst.Binary binary = (RuleAst.Binary) parsed.expression();
        assertThat(binary.left()).isInstanceOf(RuleAst.FunctionCall.class);
        assertThat(binary.right()).isInstanceOf(RuleAst.Literal.class);
    }
}
