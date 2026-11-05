package com.example.order.archive;

import com.example.order.domain.OrderRepository;
import com.example.order.domain.OrderStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ArchiveJobConfig {

    @Bean
    public Job archiveOrders(JobRepository jobs, Step archiveShipped) {
        return new JobBuilder("archiveOrders", jobs).start(archiveShipped).build();
    }

    @Bean
    public Step archiveShipped(JobRepository jobs, PlatformTransactionManager tx, OrderRepository repository) {
        return new StepBuilder("archiveShipped", jobs)
                .tasklet((contribution, context) -> {
                    // SEEDED DEFECT D8: cancelled orders are archived too; only SHIPPED orders should be
                    repository.archiveWhere(o -> o.status() == OrderStatus.SHIPPED || o.status() == OrderStatus.CANCELLED);
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }
}
