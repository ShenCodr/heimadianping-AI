package com.hmdp.controller;

import com.hmdp.service.ConsultantService;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ConsultantService consultantService;

    public ChatController(ConsultantService consultantService) {
        this.consultantService = consultantService;
    }

    @GetMapping(produces = "text/html;charset=utf-8")
    public Flux<String> chat(@MemoryId String memoryId,@UserMessage String message){
        return consultantService.chat(memoryId, message);
    }

}
