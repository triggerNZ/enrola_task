package com.enrola.web.admin;

import com.enrola.chat.Prompt;
import com.enrola.chat.PromptKind;
import com.enrola.chat.PromptService;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Reading and editing the agent's instructions.
 *
 * <p>Editing means opening a version -- any version -- and saving it, which appends a new current
 * one. Reverting is therefore not a special case: open v1, save, and it becomes the newest.
 */
@Controller
public class PromptController {

    private final PromptService prompts;

    PromptController(PromptService prompts) {
        this.prompts = prompts;
    }

    @GetMapping("/admin/prompts")
    String index(Model model) {
        List<Prompt> current = new ArrayList<>();
        for (PromptKind kind : PromptKind.values()) {
            prompts.current(kind).ifPresent(current::add);
        }
        model.addAttribute("prompts", current);
        return "admin/prompts";
    }

    @GetMapping("/admin/prompts/{kind}")
    String history(@PathVariable String kind, Model model) {
        PromptKind promptKind = requireKind(kind);
        model.addAttribute("kind", promptKind);
        model.addAttribute("history", prompts.history(promptKind));
        return "admin/prompt-history";
    }

    /** One version, in a textarea. Saving it makes a new one; this version is never overwritten. */
    @GetMapping("/admin/prompts/{kind}/{version}")
    String version(@PathVariable String kind, @PathVariable int version, Model model) {
        PromptKind promptKind = requireKind(kind);
        Prompt prompt =
                prompts
                        .find(promptKind, version)
                        .orElseThrow(
                                () ->
                                        new UnknownPromptException(
                                                "No version %d of the %s.".formatted(version, promptKind.displayName())));

        model.addAttribute("kind", promptKind);
        model.addAttribute("prompt", prompt);
        return "admin/prompt";
    }

    @PostMapping("/admin/prompts/{kind}")
    String save(
            @PathVariable String kind,
            @RequestParam String body,
            Principal author,
            RedirectAttributes attributes) {

        PromptKind promptKind = requireKind(kind);
        Prompt saved = prompts.save(promptKind, body, author == null ? null : author.getName());

        attributes.addFlashAttribute("saved", saved.version());
        return "redirect:/admin/prompts/" + promptKind.slug();
    }

    private static PromptKind requireKind(String kind) {
        PromptKind parsed = PromptKind.parse(kind);
        if (parsed == null) {
            throw new UnknownPromptException("There is no prompt called \"" + kind + "\".");
        }
        return parsed;
    }
}
