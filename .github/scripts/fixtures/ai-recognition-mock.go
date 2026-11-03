//go:build ignore

// Disposable OpenAI-compatible fixture. Prompt bodies remain in memory and are
// never logged or returned by audit endpoints. Controls accept synthetic data.
package main

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
)

type fixture struct {
	Result map[string]any `json:"result"`
	Forbidden []string `json:"forbidden"`
	Required []string `json:"required"`
	Hold bool `json:"hold"`
	Malformed bool `json:"malformed"`
}
var mu sync.Mutex
var current fixture
var release chan struct{}
var requests, violations int

func main() {
	http.HandleFunc("/control",func(w http.ResponseWriter,r *http.Request){
		if r.Method!="POST" { w.WriteHeader(405);return }
		var next fixture
		if json.NewDecoder(io.LimitReader(r.Body,65536)).Decode(&next)!=nil {w.WriteHeader(400);return}
		mu.Lock();if release!=nil {close(release);release=nil};current=next;requests=0;violations=0;if next.Hold {release=make(chan struct{})};mu.Unlock()
		w.WriteHeader(204)
	})
	http.HandleFunc("/release",func(w http.ResponseWriter,r *http.Request){mu.Lock();if release!=nil {close(release);release=nil};mu.Unlock();w.WriteHeader(204)})
	http.HandleFunc("/audit",func(w http.ResponseWriter,r *http.Request){mu.Lock();defer mu.Unlock();_ = json.NewEncoder(w).Encode(map[string]int{"requests":requests,"violations":violations})})
	http.HandleFunc("/",func(w http.ResponseWriter,r *http.Request){
		var body struct {Messages []struct{Role string `json:"role"`;Content json.RawMessage `json:"content"`} `json:"messages"`}
		if json.NewDecoder(io.LimitReader(r.Body,1048576)).Decode(&body)!=nil {w.WriteHeader(400);return}
		context:="";for _,message:=range body.Messages {if message.Role=="system" {_ = json.Unmarshal(message.Content,&context)}}
		mu.Lock();f:=current;wait:=release;requests++
		for _,name:=range f.Forbidden {if strings.Contains(context,name){violations++}}
		for _,name:=range f.Required {if !strings.Contains(context,name){violations++}}
		mu.Unlock()
		if wait!=nil {select {case <-wait:case <-r.Context().Done():return}}
		content,_:=json.Marshal(f.Result);if f.Malformed {content=[]byte(`{"synthetic_private_response":`)}
		w.Header().Set("Content-Type","application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"choices":[]any{map[string]any{"message":map[string]any{"content":string(content)}}},"usage":map[string]int{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}})
	})
	_ = http.ListenAndServe(":8080",nil)
}
