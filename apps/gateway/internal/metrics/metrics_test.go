package metrics

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func scrape(t *testing.T, m *Metrics) string {
	t.Helper()
	w := httptest.NewRecorder()
	m.Routes(http.NotFoundHandler()).ServeHTTP(w, httptest.NewRequest("GET", "/metrics", nil))
	if w.Code != 200 || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("invalid scrape %d %v", w.Code, w.Header())
	}
	return w.Body.String()
}

func TestMetricsResponsesAndPrivacy(t *testing.T) {
	m := New()
	for _, status := range []int{200, 204, 401, 403, 409, 503} {
		next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("X-Test", "preserved")
			w.WriteHeader(status)
			_, _ = w.Write([]byte("CANARY_PAYLOAD"))
		})
		req := httptest.NewRequest("POST", "/v1/executions?CANARY_QUERY", strings.NewReader("CANARY_INPUT"))
		req.Header.Set("Authorization", "Bearer CANARY_TOKEN")
		w := httptest.NewRecorder()
		m.WrapExecutionHandler(next).ServeHTTP(w, req)
		if w.Code != status || w.Body.String() != "CANARY_PAYLOAD" || w.Header().Get("X-Test") != "preserved" {
			t.Fatal("middleware changed response")
		}
	}
	body := scrape(t, m)
	for _, want := range []string{`proofmesh_gateway_execution_requests_total{status_class="2xx"} 2`, `proofmesh_gateway_execution_requests_total{status_class="4xx"} 3`, `proofmesh_gateway_execution_requests_total{status_class="5xx"} 1`, `proofmesh_gateway_execution_duration_seconds_count 6`, `proofmesh_gateway_execution_in_flight 0`} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in %s", want, body)
		}
	}
	for _, secret := range []string{"CANARY", "go_gc", "process_", "tool_name", "organization_id"} {
		if strings.Contains(body, secret) {
			t.Fatalf("unexpected scrape data %s", secret)
		}
	}
}

func TestMetricsSkipOtherRoutesAndMethods(t *testing.T) {
	m := New()
	calls := 0
	handler := m.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	for _, route := range []struct{ method, path string }{{"GET", "/v1/executions"}, {"POST", "/healthz"}, {"POST", "/v1/executions/extra"}, {"GET", "/metrics"}} {
		handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(route.method, route.path, nil))
	}
	if calls != 4 {
		t.Fatal("delegation changed")
	}
	if strings.Contains(scrape(t, m), "execution_requests_total{") {
		t.Fatal("recorded non-execution request")
	}
	routes := m.Routes(http.NotFoundHandler())
	for _, method := range []string{"HEAD", "POST", "DELETE"} {
		w := httptest.NewRecorder()
		routes.ServeHTTP(w, httptest.NewRequest(method, "/metrics", nil))
		if w.Code != 405 || w.Header().Get("Allow") != "GET" {
			t.Fatal("invalid metrics method response")
		}
	}
	w := httptest.NewRecorder()
	routes.ServeHTTP(w, httptest.NewRequest("GET", "/metrics/extra", nil))
	if w.Code != 404 {
		t.Fatal("metrics prefix matched")
	}
}

func TestMetricsConcurrencyAndRegistryIsolation(t *testing.T) {
	m := New()
	other := New()
	handler := m.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("ok")) }))
	var wg sync.WaitGroup
	for range 100 {
		wg.Go(func() { handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("POST", "/v1/executions", nil)) })
	}
	wg.Wait()
	if !strings.Contains(scrape(t, m), `status_class="2xx"} 100`) {
		t.Fatal("lost concurrent observations")
	}
	if strings.Contains(scrape(t, other), "execution_requests_total{") {
		t.Fatal("registry leaked across instances")
	}
}

func TestMetricsPanicPropagatesAndInFlightClears(t *testing.T) {
	m := New()
	func() {
		defer func() {
			if recover() != "canary panic" {
				t.Fatal("panic changed")
			}
		}()
		m.WrapExecutionHandler(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { panic("canary panic") })).ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("POST", "/v1/executions", nil))
	}()
	body := scrape(t, m)
	if !strings.Contains(body, `status_class="5xx"} 1`) || !strings.Contains(body, "execution_in_flight 0") || strings.Contains(body, "canary") {
		t.Fatal("panic metrics incorrect")
	}
}
