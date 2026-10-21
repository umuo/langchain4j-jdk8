package dev.langchain4j.agentic.internal;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import java.util.function.BiFunction;

@FunctionalInterface
public interface UserMessageTransformer extends BiFunction<ChatRequest, Object, ChatRequest> {

    @Override
    default ChatRequest apply(ChatRequest chatRequest, Object memoryId) {
        List<ChatMessage> messages = chatRequest.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                UserMessage originalMessage = (UserMessage) messages.get(i);
                UserMessage transformedMessage = UserMessage.from(transformUserMessage(originalMessage.singleText(), memoryId));
                List<ChatMessage> modifiedMessages = chatRequest.messages().stream().map(message -> message == originalMessage ? transformedMessage : message).collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), java.util.Collections::unmodifiableList));
                return ChatRequest.builder().messages(modifiedMessages).parameters(chatRequest.parameters()).build();
            }
        }
        return chatRequest;
    }

    String transformUserMessage(String userMessage, Object memoryId);
}
