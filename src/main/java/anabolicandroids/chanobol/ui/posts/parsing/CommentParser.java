/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package anabolicandroids.chanobol.ui.posts.parsing;


import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import org.jsoup.Jsoup;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import anabolicandroids.chanobol.api.data.Post;
import anabolicandroids.chanobol.ui.scaffolding.ThemeContext;

// Adapted from Clover's ChanParser

// I have changed the code a bit. For example, make it recursive in the 'b'-case such that
// the sticky of /fit/ is rendered correctly and its line breaks are preserved. I've changed
// the code around ClickableSpans to fit Chanobol's data model. In general, I'd like to
// improve the code further (e.g. make all cases recursive) and maybe simpler but only if
// there was a test suite of comments such that I can be surer that changes do not break the
// parsing. As it stands the code is obviously tried and tested in the wild and that's a
// quality in itself.

public class CommentParser {
    private static final Pattern colorPattern = Pattern.compile("color:#([0-9a-fA-F]*)");

    private static CommentParser instance = new CommentParser();

    public static CommentParser getInstance() {
        return instance;
    }

    public CommentParser() {
    }


    public CharSequence parseComment(Post post, String commentRaw) {
        CharSequence total = new SpannableString("");

        try {
            String comment = commentRaw.replace("<wbr>", "");

            Document document = Jsoup.parseBodyFragment(comment);

            List<Node> nodes = document.body().childNodes();

            for (Node node : nodes) {
                CharSequence nodeParsed = parseNode(post, node);
                if (nodeParsed != null) {
                    total = TextUtils.concat(total, nodeParsed);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return total;
    }

    public CharSequence parseComment(Post post, Node node) {
        CharSequence total = new SpannableString("");

        if (node.childNodeSize() == 0) return parseNode(post, node);

        try {
            List<Node> nodes = node.childNodes();

            for (Node n : nodes) {
                CharSequence nodeParsed = parseNode(post, n);
                if (nodeParsed != null) {
                    total = TextUtils.concat(total, nodeParsed);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return total;
    }

    private CharSequence parseNode(Post post, Node node) {
        if (node instanceof TextNode) {
            String text = ((TextNode) node).text();
            SpannableString spannable = new SpannableString(text);

            detectLinks(text, spannable);

            return spannable;
        } else {
            switch (node.nodeName()) {
                case "br": {
                    return "\n";
                }
                case "span": {
                    Element span = (Element) node;

                    SpannableString quote;

                    Set<String> classes = span.classNames();
                    if (classes.contains("deadlink")) {
                        quote = new SpannableString(span.text());
                        quote.setSpan(new ForegroundColorSpan(ThemeContext.getInstance().getQuoteColor()), 0, quote.length(), 0);
                        quote.setSpan(new StrikethroughSpan(), 0, quote.length(), 0);
                    } else if (classes.contains("fortune")) {
                        // html looks like <span class="fortune" style="color:#0893e1"><br><br><b>Your fortune:</b>
                        // manually add these <br>
                        quote = new SpannableString("\n\n" + span.text());

                        String style = span.attr("style");
                        if (!TextUtils.isEmpty(style)) {
                            style = style.replace(" ", "");

                            // private static final Pattern colorPattern = Pattern.compile("color:#([0-9a-fA-F]*)");
                            Matcher matcher = colorPattern.matcher(style);

                            int hexColor = 0xff0000;
                            if (matcher.find()) {
                                String group = matcher.group(1);
                                if (!TextUtils.isEmpty(group)) {
                                    try {
                                        hexColor = Integer.parseInt(group, 16);
                                    } catch (NumberFormatException ignored) { }
                                }
                            }

                            if (hexColor >= 0 && hexColor <= 0xffffff) {
                                quote.setSpan(new ForegroundColorSpan(0xff000000 + hexColor), 0, quote.length(), 0);
                                quote.setSpan(new StyleSpan(Typeface.BOLD), 0, quote.length(), 0);
                            }
                        }
                    } else {
                        quote = new SpannableString(span.text());
                        quote.setSpan(new ForegroundColorSpan(ThemeContext.getInstance().getInlineQuoteColor()), 0, quote.length(), 0);
                        detectLinks(span.text(), quote);
                    }

                    return quote;
                }
                case "b": {
                    Element bold = (Element) node;
                    SpannableString result = new SpannableString(parseComment(post, bold));
                    result.setSpan(new StyleSpan(Typeface.BOLD), 0, result.length(), 0);
                    return result;
                }
                case "strong": {
                    Element strong = (Element) node;

                    SpannableString red = new SpannableString(strong.text());
                    red.setSpan(new ForegroundColorSpan(ThemeContext.getInstance().getQuoteColor()), 0, red.length(), 0);
                    red.setSpan(new StyleSpan(Typeface.BOLD), 0, red.length(), 0);

                    return red;
                }
                case "a": {
                    CharSequence anchor = parseAnchor(post, (Element) node);
                    if (anchor != null) {
                        return anchor;
                    } else {
                        return ((Element) node).text();
                    }
                }
                case "s": {
                    Element spoiler = (Element) node;

                    SpannableString link = new SpannableString(spoiler.text());

                    SpoilerSpan spoilerSpan = new SpoilerSpan();
                    link.setSpan(spoilerSpan, 0, link.length(), 0);

                    return link;
                }
                case "pre": {
                    Element pre = (Element) node;

                    Set<String> classes = pre.classNames();
                    if (classes.contains("prettyprint")) {
                        String text = getNodeText(pre);
                        SpannableString monospace = new SpannableString(text);
                        monospace.setSpan(new TypefaceSpan("monospace"), 0, monospace.length(), 0);
                        monospace.setSpan(new AbsoluteSizeSpan(ThemeContext.getInstance().getCodeTagSize()), 0, monospace.length(), 0);
                        return monospace;
                    } else {
                        return pre.text();
                    }
                }
                default: {
                    // Unknown tag, add the inner part
                    if (node instanceof Element) {
                        return ((Element) node).text();
                    } else {
                        return null;
                    }
                }
            }
        }
    }

    private CharSequence parseAnchor(Post post, Element anchor) {
        String href = anchor.attr("href");
        Set<String> classes = anchor.classNames();
        if (classes.contains("quotelink")) {
            if (href.contains("/thread/")) {
                // link to another thread
                ThreadLink threadLink = null;

                String[] slashSplit = href.split("/");
                if (slashSplit.length == 4) {
                    String board = slashSplit[1];
                    String nums = slashSplit[3];
                    String[] numsSplitted = nums.split("#p");
                    if (numsSplitted.length == 2) {
                        try {
                            int tId = Integer.parseInt(numsSplitted[0]);
                            int pId = Integer.parseInt(numsSplitted[1]);
                            threadLink = new ThreadLink(board, String.valueOf(tId), String.valueOf(pId));
                        } catch (NumberFormatException ignored) { }
                    }
                }
                if (threadLink == null) return null;

                SpannableString link = new SpannableString(anchor.text() + " \u2192"); // arrow to the right
                ThreadSpan threadSpan = new ThreadSpan(threadLink);
                link.setSpan(threadSpan, 0, link.length(), 0);
                return link;
            } else {
                // normal quote
                int id = -1;

                String[] splitted = href.split("#p");
                if (splitted.length == 2) {
                    try { id = Integer.parseInt(splitted[1]); } catch (NumberFormatException ignored) { }
                }
                if (id < 0) return null;

                String quote = anchor.text();
                // Append OP when its a reply to OP
                if (String.valueOf(id).equals(post.replyTo)) {
                    quote = quote + " (OP)";
                }

                SpannableString link = new SpannableString(quote);
                QuoteSpan quoteSpan = new QuoteSpan(post.number, String.valueOf(id));
                link.setSpan(quoteSpan, 0, link.length(), 0);
                return link;
            }
        } else {
            // normal link
            SpannableString span = new SpannableString(parseComment(post, anchor));
            LinkSpan linkSpan = new LinkSpan(href);
            span.setSpan(linkSpan, 0, span.length(), 0);
            return span;
        }
    }

    private void detectLinks(String text, SpannableString spannable) {
        int startPos = 0;
        int endPos;
        while (true) {
            startPos = text.indexOf("://", startPos);
            if (startPos < 0) break;

            // go back to the first space
            while (startPos > 0 && !isWhitespace(text.charAt(startPos - 1))) {
                startPos--;
            }

            // find the last non whitespace character
            endPos = startPos;
            while (endPos < text.length() - 1 && !isWhitespace(text.charAt(endPos + 1))) {
                endPos++;
            }

            // one past
            endPos++;

            String linkString = text.substring(startPos, endPos);

            LinkSpan linkSpan = new LinkSpan(linkString);
            spannable.setSpan(linkSpan, startPos, endPos, 0);

            startPos = endPos;
        }
    }

    private boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || c == '>'; // consider > as a link separator
    }

    // Below code taken from org.jsoup.nodes.Element.text(), but it preserves <br>
    private String getNodeText(Element node) {
        final StringBuilder accum = new StringBuilder();
        new NodeTraversor(new NodeVisitor() {
            public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    TextNode textNode = (TextNode) node;
                    appendNormalisedText(accum, textNode);
                } else if (node instanceof Element) {
                    Element element = (Element) node;
                    if (accum.length() > 0 &&
                            element.isBlock() &&
                            !lastCharIsWhitespace(accum))
                        accum.append(" ");

                    if (element.tag().getName().equals("br")) {
                        accum.append("\n");
                    }
                }
            }

            public void tail(Node node, int depth) {
            }
        }).traverse(node);
        return accum.toString().trim();
    }

    private static boolean lastCharIsWhitespace(StringBuilder sb) {
        return sb.length() != 0 && sb.charAt(sb.length() - 1) == ' ';
    }

    private static void appendNormalisedText(StringBuilder accum, TextNode textNode) {
        String text = textNode.getWholeText();

        if (!preserveWhitespace(textNode.parent())) {
            text = normaliseWhitespace(text);
            if (lastCharIsWhitespace(accum))
                text = stripLeadingWhitespace(text);
        }
        accum.append(text);
    }

    private static String normaliseWhitespace(String text) {
        text = StringUtil.normaliseWhitespace(text);
        return text;
    }

    private static String stripLeadingWhitespace(String text) {
        return text.replaceFirst("^\\s+", "");
    }

    private static boolean preserveWhitespace(Node node) {
        // looks only at this element and one level up, to prevent recursion & needless stack searches
        if (node != null && node instanceof Element) {
            Element element = (Element) node;
            return element.tag().preserveWhitespace() ||
                    element.parent() != null && element.parent().tag().preserveWhitespace();
        }
        return false;
    }
}
