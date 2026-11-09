package com.example.lending.recall;

import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cloud Stream binds recall to recall-in-0 (recall notices) and recall-out-0 (return instructions). */
@Configuration
public class RecallFunctions {
    @Bean
    public Function<RecallNotice, ReturnInstruction> recall() {
        // seeded defect D20: a recall larger than the borrow returns more shares than were borrowed
        return notice -> new ReturnInstruction(notice.borrowId(), notice.recalledQuantity());
    }
}
