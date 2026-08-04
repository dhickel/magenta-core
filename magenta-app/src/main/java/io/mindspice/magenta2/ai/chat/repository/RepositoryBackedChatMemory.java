package io.mindspice.magenta2.ai.chat.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

public class RepositoryBackedChatMemory implements ChatMemory {

    private final ChatMemoryRepository chatMemoryRepository;

    public RepositoryBackedChatMemory(ChatMemoryRepository chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        List<Message> conversation = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
        conversation.addAll(messages);
        chatMemoryRepository.saveAll(conversationId, conversation);
    }

    @Override
    public List<Message> get(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return chatMemoryRepository.findByConversationId(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        chatMemoryRepository.deleteByConversationId(conversationId);
    }
}
