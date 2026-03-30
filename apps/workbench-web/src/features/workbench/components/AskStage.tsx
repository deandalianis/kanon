import {useMutation} from "@tanstack/react-query";
import {useEffect, useRef, useState} from "react";
import {api, asJson} from "../../../api";
import type {ChatAnswer} from "../../../types";
import type {ChatMessage} from "../types";
import {EmptyState, Panel, SectionHeader} from "./primitives";

export function AskStage({
                             projectId,
                             hasSpec
                         }: {
    projectId: string;
    hasSpec: boolean;
}) {
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [question, setQuestion] = useState("");
    const bottomRef = useRef<HTMLDivElement>(null);

    const askMutation = useMutation({
        mutationFn: (q: string) =>
            api<ChatAnswer>(`/api/projects/${projectId}/ask`, asJson({question: q})),
        onSuccess: (data, q) => {
            setMessages((prev) => [
                ...prev,
                {role: "user", content: q},
                {role: "assistant", content: data.answer}
            ]);
            setQuestion("");
        }
    });

    useEffect(() => {
        bottomRef.current?.scrollIntoView({behavior: "smooth"});
    }, [messages]);

    function handleSubmit(event: React.FormEvent) {
        event.preventDefault();
        if (!question.trim() || askMutation.isPending) return;
        askMutation.mutate(question.trim());
    }

    function handleKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            if (!question.trim() || askMutation.isPending) return;
            askMutation.mutate(question.trim());
        }
    }

    return (
        <div className="stage-grid">
            <div className="stage-main">
                <Panel>
                    <SectionHeader
                        eyebrow="Ask"
                        title="Ask the spec"
                        description="Ask questions about this service's domain model. Answers are grounded in the approved semantic spec and cited evidence."
                    />

                    {!hasSpec ? (
                        <EmptyState
                            title="No approved spec"
                            detail="An approved semantic spec is required before asking questions. Refresh knowledge first."
                        />
                    ) : (
                        <div className="chat-shell">
                            <div className="chat-messages">
                                {messages.length === 0 && (
                                    <EmptyState
                                        title="No questions yet"
                                        detail="Try: 'What commands does the VacationPlan aggregate expose?' or 'What rules govern holiday creation?'"
                                    />
                                )}
                                {messages.map((msg, index) => (
                                    <div
                                        key={index}
                                        className={`chat-message chat-message--${msg.role}`}
                                    >
                    <span className="chat-message-role">
                      {msg.role === "user" ? "You" : "Spec"}
                    </span>
                                        <p className="chat-message-content">{msg.content}</p>
                                    </div>
                                ))}
                                {askMutation.isPending && (
                                    <div className="chat-message chat-message--assistant chat-message--pending">
                                        <span className="chat-message-role">Spec</span>
                                        <p className="chat-message-content">Thinking…</p>
                                    </div>
                                )}
                                {askMutation.isError && (
                                    <div className="chat-message chat-message--error">
                                        <p className="chat-message-content">
                                            {askMutation.error instanceof Error
                                                ? askMutation.error.message
                                                : "Request failed"}
                                        </p>
                                    </div>
                                )}
                                <div ref={bottomRef}/>
                            </div>

                            <form className="chat-input-row" onSubmit={handleSubmit}>
                <textarea
                    className="chat-input"
                    rows={3}
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="Ask about aggregates, commands, rules, scenarios… (Enter to send, Shift+Enter for newline)"
                    disabled={askMutation.isPending}
                />
                                <button
                                    type="submit"
                                    className="primary-button"
                                    disabled={askMutation.isPending || !question.trim()}
                                >
                                    {askMutation.isPending ? "Asking…" : "Ask"}
                                </button>
                            </form>
                        </div>
                    )}
                </Panel>
            </div>
        </div>
    );
}
