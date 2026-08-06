package com.enrola.web.admin;

import com.enrola.chat.ChatService;
import com.enrola.chat.ConversationMetrics;
import com.enrola.chat.ConversationSummary;
import com.enrola.chat.MessageView;
import com.enrola.chat.PromptService;
import com.enrola.chat.ReviewService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Reading conversations and saying what was wrong with them.
 *
 * <p>Server-rendered pages, not JSON: a {@code @Controller} returning view names. Saving is
 * post-redirect-get, so refreshing the page after a review does not submit it again.
 */
@Controller
public class AdminController {

    /** Enough to scroll through; paging is not worth building until it is not. */
    private static final int PAGE_SIZE = 100;

    private final ChatService chat;
    private final ReviewService reviews;
    private final PromptService prompts;

    AdminController(ChatService chat, ReviewService reviews, PromptService prompts) {
        this.chat = chat;
        this.reviews = reviews;
        this.prompts = prompts;
    }

    @GetMapping("/admin")
    String index() {
        return "redirect:/admin/conversations";
    }

    @GetMapping("/admin/conversations")
    String conversations(
            @RequestParam(defaultValue = "false") boolean unreviewed, Model model) {
        model.addAttribute("conversations", reviews.awaitingReview(unreviewed, PAGE_SIZE));
        model.addAttribute("unreviewed", unreviewed);
        return "admin/conversations";
    }

    @GetMapping("/admin/conversations/{id}")
    String conversation(@PathVariable UUID id, Model model) {
        ConversationSummary conversation = chat.get(id);
        List<MessageView> messages = chat.transcript(id);
        model.addAttribute("conversation", conversation);
        model.addAttribute("messages", messages);
        model.addAttribute("metrics", ConversationMetrics.from(messages));
        model.addAttribute("review", reviews.find(id).orElse(null));
        // What the agent was told at the time, and whether that is still what it is told.
        model.addAttribute("promptsUsed", prompts.usedBy(id));
        model.addAttribute("currentPrompts", prompts.currentSet());
        return "admin/conversation";
    }

    @PostMapping("/admin/conversations/{id}/review")
    String review(
            @PathVariable UUID id,
            @RequestParam int rating,
            @RequestParam(required = false) String comment,
            Principal reviewer,
            RedirectAttributes attributes) {

        reviews.save(id, rating, comment, reviewer == null ? null : reviewer.getName());
        attributes.addFlashAttribute("saved", true);
        return "redirect:/admin/conversations/" + id;
    }
}
