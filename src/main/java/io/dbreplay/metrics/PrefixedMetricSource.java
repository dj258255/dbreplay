package io.dbreplay.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다른 곳에서 읽은 지표의 이름 앞에 접두어를 붙인다. 종 DB에서 뜬 지표를 주 DB의 것과 같은 표본기에 넣으면서
 * 이름으로 가르려는 것이다(#80). 알람의 판정 이유에 이름이 그대로 남아, 종 DB의 알람이 울렸는지 알 수 있다.
 */
public final class PrefixedMetricSource implements MetricSource {

	private final String prefix;
	private final MetricSource source;

	public PrefixedMetricSource(String prefix, MetricSource source) {
		this.prefix = prefix;
		this.source = source;
	}

	@Override
	public Map<String, Double> read() throws Exception {
		Map<String, Double> prefixed = new LinkedHashMap<>();
		source.read().forEach((name, value) -> prefixed.put(prefix + name, value));
		return prefixed;
	}

	@Override
	public List<String> failures() {
		return source.failures().stream().map(failure -> prefix + " " + failure).toList();
	}

	@Override
	public void close() {
		source.close();
	}
}
