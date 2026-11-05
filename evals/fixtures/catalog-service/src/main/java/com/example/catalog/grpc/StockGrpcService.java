package com.example.catalog.grpc;

import com.example.catalog.domain.ProductRepository;
import com.example.catalog.grpc.proto.StockReply;
import com.example.catalog.grpc.proto.StockRequest;
import com.example.catalog.grpc.proto.StockServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

@Service
public class StockGrpcService extends StockServiceGrpc.StockServiceImplBase {
    private final ProductRepository repository;

    public StockGrpcService(ProductRepository repository) {
        this.repository = repository;
    }

    @Override
    public void getStock(StockRequest request, StreamObserver<StockReply> reply) {
        repository.findById(request.getProductId())
                .blockOptional()
                .ifPresentOrElse(
                        p -> {
                            reply.onNext(StockReply.newBuilder()
                                    .setProductId(p.id())
                                    .setStock(p.stock())
                                    // SEEDED DEFECT D12: >= instead of >, so a product with no stock is reported available
                                    .setAvailable(p.stock() >= 0)
                                    .build());
                            reply.onCompleted();
                        },
                        () -> reply.onError(Status.NOT_FOUND.withDescription(request.getProductId()).asRuntimeException()));
    }
}
