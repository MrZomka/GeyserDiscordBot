/*
 * Copyright (c) 2020-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/GeyserDiscordBot
 */

package org.geysermc.discordbot.listeners;

import com.rtm516.stackparser.Parser;
import com.rtm516.stackparser.StackException;
import com.rtm516.stackparser.StackLine;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.geysermc.discordbot.storage.ServerSettings;
import org.geysermc.discordbot.tags.TagsManager;
import org.geysermc.discordbot.util.BotColors;
import org.geysermc.discordbot.util.BotHelpers;
import org.geysermc.discordbot.util.GithubFileFinder;
import org.geysermc.discordbot.util.MessageHelper;
import pw.chew.chewbotcca.util.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErrorAnalyzer extends ListenerAdapter {
    private final Map<Pattern, String> logUrlPatterns;

    private static final Pattern BRANCH_PATTERN = Pattern.compile("Geyser .* \\(git-[0-9a-zA-Z]+-([0-9a-zA-Z]{7})\\)");

    public ErrorAnalyzer() {
        logUrlPatterns = new HashMap<>();

        // Log url patterns
        logUrlPatterns.put(Pattern.compile("hastebin\\.com/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://hastebin.com/raw/%s");
        logUrlPatterns.put(Pattern.compile("hasteb\\.in/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://hasteb.in/raw/%s");
        logUrlPatterns.put(Pattern.compile("mclo\\.gs/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://api.mclo.gs/1/raw/%s");
        logUrlPatterns.put(Pattern.compile("pastebin\\.com/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://pastebin.com/raw/%s");
        logUrlPatterns.put(Pattern.compile("gist\\.github\\.com/([0-9a-zA-Z]+)/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://gist.githubusercontent.com/%1$s/%2$s/raw/");
        logUrlPatterns.put(Pattern.compile("paste\\.shockbyte\\.com/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://paste.shockbyte.com/raw/%s");
        logUrlPatterns.put(Pattern.compile("pastie\\.io/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://pastie.io/raw/%s");
        logUrlPatterns.put(Pattern.compile("rentry\\.co/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://rentry.co/%s/raw");
        logUrlPatterns.put(Pattern.compile("pastebin.pl/view/([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE), "https://pastebin.pl/view/raw/%s");
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        // Check attachments
        for (Message.Attachment attachment : event.getMessage().getAttachments()) {
            List<String> extensions;

            // Get the guild extensions and if not in a guild just use some defaults
            if (event.isFromGuild()) {
                extensions = ServerSettings.getList(event.getGuild().getIdLong(), "convert-extensions");
            } else {
                extensions = new ArrayList<>();
                extensions.add("txt");
                extensions.add("log");
                extensions.add("yml");
                extensions.add("0");
            }

            if (extensions.contains(attachment.getFileExtension())) {
                handleLog(event, RestClient.get(attachment.getUrl()));
            }
        }

        // Check the message for urls
        String rawContent = event.getMessage().getContentRaw();
        String url = null;
        for (Pattern regex : logUrlPatterns.keySet()) {
            Matcher matcher = regex.matcher(rawContent);

            if (!matcher.find()) {
                continue;
            }

            String[] groups = new String[matcher.groupCount()];
            for (int i = 0; i < matcher.groupCount(); i++) {
                groups[i] = matcher.group(i + 1);
                groups[i] = groups[i] == null ? "" : groups[i]; // Replace nulls with empty strings
            }

            url = String.format(logUrlPatterns.get(regex), (Object) groups);
            break;
        }

        String content;
        if (url == null) {
            content = rawContent;
        } else {
            // We didn't find a url so use the message content
            content = RestClient.get(url);
        }

        handleLog(event, content);
    }

    /**
     * Handle the log content and output any errors
     *
     * @param event Message to respond to
     * @param logContent The log to check
     */
    private void handleLog(MessageReceivedEvent event, String logContent) {
        // Create the embed and format it
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle("Found errors in the log!");
        embedBuilder.setDescription("See below for details and possible fixes");
        embedBuilder.setColor(BotColors.FAILURE.getColor());

        List<StackException> exceptions = Parser.parse(logContent);

        int embedLength = embedBuilder.length();

        // Add any errors that aren't from stack traces first
        for (String issue : TagsManager.getIssueResponses().keySet()) {
            if (embedLength >= MessageEmbed.EMBED_MAX_LENGTH_BOT || embedBuilder.getFields().size() >= 25) {
                // cannot have more than 25 embed fields
                break;
            }

            if (logContent.contains(issue)) {
                String title = BotHelpers.trim(issue, MessageEmbed.TITLE_MAX_LENGTH);

                if (MessageHelper.similarFieldExists(embedBuilder.getFields(), title)) {
                    continue;
                }

                String fix = BotHelpers.trim(TagsManager.getIssueResponses().get(issue), MessageEmbed.VALUE_MAX_LENGTH);
                embedBuilder.addField(title, fix, false);
                embedLength += title.length() + fix.length();
            }
        }

        // Add any errors from stacktraces
        if (exceptions.size() != 0) {
            // Get the github trees for fetching the file paths
            String branch = "master";
            Matcher branchMatcher = BRANCH_PATTERN.matcher(logContent);
            if (branchMatcher.find()) {
                branch = branchMatcher.group(1);
            }

            GithubFileFinder fileFinder = new GithubFileFinder(branch);
            for (StackException exception : exceptions) {
                if (embedLength >= MessageEmbed.EMBED_MAX_LENGTH_BOT || embedBuilder.getFields().size() >= 25) {
                    break;
                }

                String exceptionTitle = (exception.getException() + ": " + exception.getDescription()).trim();

                // If there is a fix for the exception, add it.
                int exitCode = addFixIfPresent(exceptionTitle, embedBuilder);
                if (exitCode > 0) {
                    embedLength += exitCode;
                } else if (exitCode == -1) {
                    // The error was not already listed and no fix was found. Add some info about the error

                    for (StackLine line : exception.getLines()) {
                        if (line.getStackPackage() != null && line.getStackPackage().startsWith("org.geysermc") && !line.getStackPackage().contains("shaded")) {
                            // Get the file url
                            String lineUrl = fileFinder.getFileUrl(line.getSource(), Integer.parseInt(line.getLine()));

                            // Build the description
                            String details = "Unknown fix!\nClass: `" + line.getJavaClass() + "`\nMethod: `" + line.getMethod() + "`\nLine: `" + line.getLine() + "`\nLink: " + (!lineUrl.isEmpty() ? "[" + line.getSource() + "#L" + line.getLine() + "](" + lineUrl + ")" : "Unknown");

                            String trimmedTitle = BotHelpers.trim(exceptionTitle, MessageEmbed.TITLE_MAX_LENGTH);
                            embedBuilder.addField(trimmedTitle, details, false);
                            embedLength += trimmedTitle.length() + details.length();

                            break;
                        }
                    }
                }
            }
        }

        boolean hasResponses = embedBuilder.getFields().size() > 0;
        if (exceptions.size() > 0 || hasResponses) {
            // Set the description accordingly if nothing Geyser related was found
            if (hasResponses) {
                MessageHelper.truncateFields(embedBuilder);
            } else {
                embedBuilder.setDescription("We don't currently have automated responses for the detected errors!");
            }

            event.getMessage().replyEmbeds(embedBuilder.build()).queue();
        }
    }

    /**
     * Add an issue and its fix to an {@link EmbedBuilder} if the fix exists, and the issue hasn't already been added
     *
     * @param issue The issue to find the fix for
     * @param embedBuilder The embed builder to add to
     * @return A positive integer equal to the size of the {@link MessageEmbed.Field} added to the {@link EmbedBuilder} if the issue wasn't already listed and a fix exists.
     * Will return -2 if the issue was already listed. Will return -1 if the issue was not already listed and no fix was found.
     */
    private int addFixIfPresent(String issue, EmbedBuilder embedBuilder) {
        // Cut down the issue so that comparisons to existing fields work
        String fieldTitle = BotHelpers.trim(issue, MessageEmbed.TITLE_MAX_LENGTH);

        if (MessageHelper.similarFieldExists(embedBuilder.getFields(), fieldTitle)) {
            return -2;
        }

        String lowerCaseIssue = issue.toLowerCase();
        String fix = null;
        for (String key : TagsManager.getIssueResponses().keySet()) {
            String lowerCaseKey = key.toLowerCase();
            if (lowerCaseIssue.contains(lowerCaseKey)) {
                fix = TagsManager.getIssueResponses().get(key);
                break;
            }
        }

        if (fix == null) {
            return -1;
        } else {
            String response = BotHelpers.trim(fix, MessageEmbed.VALUE_MAX_LENGTH);

            embedBuilder.addField(fieldTitle, response, false);
            return fieldTitle.length() + response.length();
        }
    }
}