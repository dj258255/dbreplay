package io.dbreplay.serve;

/** {@code /api/openapi.json}으로 내는 API 설명. 다른 서비스가 이것을 보고 재생을 맡긴다(원문 대조 7, #88). */
final class OpenApi {

	private OpenApi() {
	}

	static final String DOCUMENT = """
			{
			  "openapi": "3.0.3",
			  "info": {"title": "dbreplay", "version": "1"},
			  "components": {"securitySchemes": {"token": {"type": "http", "scheme": "bearer"}}},
			  "security": [{"token": []}],
			  "paths": {
			    "/api/evaluations": {"get": {"summary": "작업 폴더의 평가 이름들", "responses": {"200": {"description": "이름의 배열"}}}},
			    "/api/evaluations/{name}": {"get": {"summary": "평가 파일 하나",
			      "parameters": [{"name": "name", "in": "path", "required": true, "schema": {"type": "string"}}],
			      "responses": {"200": {"description": "평가"}, "400": {"description": "없는 이름"}}}},
			    "/api/targets": {"get": {"summary": "대상별 수위, 판정, 상한, 예산", "responses": {"200": {"description": "대상의 배열"}}}},
			    "/api/replays": {"post": {"summary": "재생을 맡긴다. 한 번에 하나만 받는다. 대상은 대상 목록의 이름이다",
			      "requestBody": {"required": true, "content": {"application/json": {"schema": {"type": "object",
			        "required": ["trace", "target"],
			        "properties": {"trace": {"type": "string", "description": "작업 폴더 안의 트래픽 파일 이름"},
			          "target": {"type": "string", "description": "대상 목록에 등록된 이름"},
			          "speed": {"type": "number", "default": 1},
			          "unit": {"type": "string", "enum": ["transaction", "statement", "session"], "default": "transaction"}}}}}},
			      "responses": {"202": {"description": "id와 상태 주소"}, "409": {"description": "다른 재생이 돌고 있다"}}}},
			    "/api/replays/{id}": {"get": {"summary": "맡긴 재생의 상태(running, done, failed)와 요약",
			      "parameters": [{"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}}],
			      "responses": {"200": {"description": "상태"}, "404": {"description": "없는 id"}}}}
			  }
			}
			""";
}
