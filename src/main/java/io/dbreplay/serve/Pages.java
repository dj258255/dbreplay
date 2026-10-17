package io.dbreplay.serve;

import java.util.List;
import java.util.Map;

import io.dbreplay.explore.CeilingRange;
import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.WaterLevel;

/** 서버가 그리는 HTML. 글자는 전부 이스케이프한다(평가 파일과 리포트에는 SQL이 든다). */
final class Pages {

	private Pages() {
	}

	private static final String HEAD = """
			<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
			<title>dbreplay</title><style>
			body{font-family:system-ui,sans-serif;margin:2rem;max-width:72rem;line-height:1.5}
			table{border-collapse:collapse;margin:1rem 0}td,th{border:1px solid #ccc;padding:.3rem .6rem;text-align:left}
			pre{white-space:pre-wrap;background:#f6f6f6;padding:1rem}
			</style></head><body>""";

	static String index(Map<String, Evaluation> evaluations, List<Map<String, Object>> targets, List<String> reports) {
		StringBuilder s = new StringBuilder(HEAD).append("<h1>dbreplay</h1>");
		if (!targets.isEmpty()) {
			s.append("<h2>대상</h2><table><tr><th>대상</th><th>수위</th><th>판정</th><th>상한</th><th>평가한 시각</th><th>권고 노드</th><th>비용 변화</th></tr>");
			for (Map<String, Object> t : targets) {
				@SuppressWarnings("unchecked")
				Map<String, Object> budget = (Map<String, Object>) t.get("budget");
				s.append("<tr><td>").append(esc(t.get("name"))).append("</td><td>")
						.append(t.containsKey("level") ? String.format("%.0f%%", (Double) t.get("level") * 100) : esc(t.get("state")))
						.append("</td><td>").append(t.containsKey("verdict") ? verdict(String.valueOf(t.get("verdict"))) : "-")
						.append("</td><td>").append(t.containsKey("ceilingSpeed") ? String.format("%.2f배속", (Double) t.get("ceilingSpeed")) : "-")
						.append("</td><td>").append(esc(t.getOrDefault("evaluatedAt", "-")))
						.append("</td><td>").append(budget == null ? "-" : budget.get("readNodes") + " → " + budget.get("recommendedReadNodes"))
						.append("</td><td>").append(budget == null ? "-" : String.format("%+,.0f", (Double) budget.get("costChange")))
						.append("</td></tr>");
			}
			s.append("</table><p>권고는 읽기 상한이 노드 수에 비례한다는 가정이다. 확인된 것이 아니다.</p>");
		}
		s.append("<h2>평가</h2><table><tr><th>이름</th><th>상한</th><th>수위</th><th>판정</th><th>평가한 시각</th></tr>");
		evaluations.forEach((name, e) -> {
			WaterLevel l = e.level();
			s.append("<tr><td><a href=\"/evaluations/").append(esc(name)).append("\">").append(esc(name)).append("</a></td><td>")
					.append(String.format("%.2f배속", e.ceilingSpeed())).append("</td><td>").append(String.format("%.0f%%", l.level() * 100))
					.append("</td><td>").append(verdict(l.verdict().name())).append("</td><td>").append(esc(e.evaluatedAt()))
					.append("</td></tr>");
		});
		s.append("</table><h2>리포트</h2><ul>");
		reports.forEach(r -> s.append("<li><a href=\"/reports/").append(esc(r)).append("\">").append(esc(r)).append("</a></li>"));
		return s.append("</ul><p>API: <a href=\"/api/openapi.json\">/api/openapi.json</a></p></body></html>").toString();
	}

	static String evaluation(String name, Evaluation e) {
		StringBuilder s = new StringBuilder(HEAD).append("<p><a href=\"/\">처음으로</a></p><h1>").append(esc(name)).append("</h1>");
		WaterLevel l = e.level();
		s.append(String.format("<p>상한 %.2f배속 (초당 %.0f문장), 수위 %.0f%% → %s. 평가한 시각 %s, %d일 동안 유효</p>", e.ceilingSpeed(),
				e.ceilingQps(), l.level() * 100, verdict(l.verdict().name()), esc(e.evaluatedAt()), e.validDays()));
		CeilingRange range = e.ceilingRange();
		if (range != null) {
			s.append(String.format("<p>되풀이한 탐색 %d번: 최소 %.2f, 중앙값 %.2f, 최대 %.2f배속. 위의 상한은 가장 낮은 값이다</p>", range.count(),
					range.min(), range.median(), range.max()));
		}
		if (e.replica() != null) {
			WaterLevel r = e.replica().level(e.low(), e.high());
			s.append(String.format("<p>종 DB: 상한 초당 %.0f, 수위 %.0f%% → %s</p>", e.replica().ceilingQps(), r.level() * 100,
					verdict(r.verdict().name())));
		}
		if (e.explorations() != null) {
			int i = 1;
			for (List<Evaluation.RoundLog> rounds : e.explorations()) {
				s.append("<h2>상승 과정").append(e.explorations().size() > 1 ? " " + i : "").append("</h2>")
						.append("<table><tr><th>배속</th><th>판정</th><th>이유</th><th>왜곡</th></tr>");
				for (Evaluation.RoundLog r : rounds) {
					s.append(String.format("<tr><td>%.2f</td><td>%s</td><td>%s</td><td>%.1f%%</td></tr>", r.speed(), esc(r.verdict()),
							esc(r.reason() == null ? "" : r.reason()), r.distortion() * 100));
				}
				s.append("</table>");
				i++;
			}
		}
		return s.append("</body></html>").toString();
	}

	static String report(String name, String markdown) {
		return HEAD + "<p><a href=\"/\">처음으로</a></p><h1>" + esc(name) + "</h1><pre>" + esc(markdown) + "</pre></body></html>";
	}

	private static String verdict(String name) {
		return switch (name) {
			case "SURPLUS" -> "과잉";
			case "HEALTHY" -> "적정";
			case "SHORT" -> "부족";
			default -> esc(name);
		};
	}

	static String esc(Object value) {
		return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
				.replace("'", "&#39;");
	}
}
