package com.nl.observe.poc.receiver;

/**
 * 样例 OTLP/JSON 载荷工具。独立类避免 ExportFailureHookIT 跨类静态调用
 * ReceiverOtlpKafkaIT 触发其 static 块启动 KafkaContainer（JLS 12.4.1）。
 */
final class SamplePayloads {

    private SamplePayloads() {
    }

    static String sampleTraceJson(String customerId, String spanName) {
        return """
        {
          "resourceSpans": [{
            "resource": { "attributes": [
              {"key":"service.name","value":{"stringValue":"poc-vue"}},
              {"key":"nl.customer_id","value":{"stringValue":"%s"}}
            ]},
            "scopeSpans": [{ "scope": {"name":"nl-observe-poc"}, "spans": [{
              "traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
              "spanId":"00f067aa0e942000",
              "name":"%s",
              "kind":1,
              "startTimeUnixNano":"1759000000000000000",
              "endTimeUnixNano":"1759000045000000000",
              "attributes":[
                {"key":"biz.code","value":{"stringValue":"ORDER"}},
                {"key":"biz.status","value":{"stringValue":"success"}}
              ],
              "status": {"code":1}
            }]}]
          }]
        }
        """.formatted(customerId, spanName);
    }
}
