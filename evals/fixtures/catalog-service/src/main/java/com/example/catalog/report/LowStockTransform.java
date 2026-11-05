package com.example.catalog.report;

import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptors;

/** The nightly low-stock report: the names of the products that need restocking, from (name, stock) pairs. */
public class LowStockTransform extends PTransform<PCollection<KV<String, Integer>>, PCollection<String>> {
    static final int LOW_STOCK_BELOW = 3;

    @Override
    public PCollection<String> expand(PCollection<KV<String, Integer>> stock) {
        // D15 (seeded defect): LOW_STOCK_BELOW - 1, so a product with exactly 2 in stock is left out of the report
        return stock.apply("LowStock", Filter.by(kv -> kv.getValue() < LOW_STOCK_BELOW - 1))
                .apply("Names", MapElements.into(TypeDescriptors.strings()).via(KV::getKey));
    }
}
