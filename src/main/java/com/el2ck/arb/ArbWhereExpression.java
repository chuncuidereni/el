package com.el2ck.arb;

import com.el2ck.exception.ApiException;
import java.util.*;

/**
 * 【文件职责】解析 ARB 源表 WHERE 的受限表达式语法。
 * 仅允许列、常量、算术/比较/逻辑运算和显式白名单中的纯函数；不接受 SQL 语句、子查询、
 * 表函数、字典读取、UDF 或查询设置。必须消费全部 token，不能靠黑名单拦截片段。
 */
final class ArbWhereExpression {
    private static final Set<String> FUNCTIONS = new HashSet<>(Arrays.asList(
            "lower", "upper", "lowerutf8", "upperutf8", "length", "lengthutf8", "empty", "notempty",
            "trim", "trimleft", "trimright", "trimboth", "substring", "substringutf8", "concat",
            "startswith", "endswith", "position", "positionutf8", "replaceall", "abs", "round", "floor", "ceil",
            "tostring", "todate", "todatetime", "toyyyymm", "toyyyymmdd", "toyear", "tomonth", "todayofmonth",
            "toint32", "toint64", "touint32", "touint64", "tofloat64", "isnull", "isnotnull", "ifnull",
            "nullif", "coalesce", "if"));
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "and", "or", "not", "in", "between", "like", "ilike", "is", "select", "from", "union",
            "with", "join", "as", "settings", "format", "limit", "offset", "into", "exists", "global"));
    private final List<Token> tokens = new ArrayList<>();
    private int position, depth;

    private ArbWhereExpression(String text) { tokenize(text); }
    static void validate(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (text.length() > 4096) throw invalid();
        ArbWhereExpression parser = new ArbWhereExpression(text);
        parser.expression();
        if (parser.position != parser.tokens.size()) throw invalid();
    }
    private static ApiException invalid() {
        return ApiException.validation("过滤条件仅支持源表列、常量、比较/逻辑运算和常用纯函数；不支持子查询、表函数或完整 SQL");
    }
    private static final class Token {
        final String text; final char kind;
        Token(String text, char kind) { this.text = text; this.kind = kind; }
    }
    private void tokenize(String text) {
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '\'' || c == '`' || c == '"') {
                char quote = c; int start = ++i; boolean closed = false;
                StringBuilder value = new StringBuilder();
                while (i < text.length()) {
                    c = text.charAt(i++);
                    if (c == '\\') {
                        if (quote != '\'' || i == text.length()) throw invalid();
                        value.append(text.charAt(i++));
                    } else if (c == quote) {
                        if (i < text.length() && text.charAt(i) == quote) { value.append(c); i++; }
                        else { closed = true; break; }
                    } else value.append(c);
                }
                if (!closed || i <= start) throw invalid();
                if (quote != '\'' && !value.toString().matches("[\\p{L}_][\\p{L}\\p{N}_]*")) throw invalid();
                tokens.add(new Token(value.toString(), quote == '\'' ? 'v' : 'q')); continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
                tokens.add(new Token(text.substring(start, i), 'i')); continue;
            }
            if (c >= '0' && c <= '9') {
                int start = i++;
                while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) i++;
                String number = text.substring(start, i);
                if (!number.matches("[0-9]+(\\.[0-9]+)?")) throw invalid();
                tokens.add(new Token(number, 'v')); continue;
            }
            String pair = i + 1 < text.length() ? text.substring(i, i + 2) : "";
            if (pair.equals("--") || pair.equals("/*") || pair.equals("*/")) throw invalid();
            if (Arrays.asList("<=", ">=", "!=", "<>", "==").contains(pair)) { tokens.add(new Token(pair,'o')); i+=2; }
            else if ("()=<>!+-*/%,.".indexOf(c) >= 0) { tokens.add(new Token(String.valueOf(c),'o')); i++; }
            else throw invalid();
        }
    }
    private boolean take(String text) {
        if (position < tokens.size() && tokens.get(position).kind != 'v' && tokens.get(position).kind != 'q'
                && tokens.get(position).text.equalsIgnoreCase(text)) { position++; return true; }
        return false;
    }
    private void require(String text) { if (!take(text)) throw invalid(); }
    private void expression() {
        if (++depth > 32) throw invalid();
        and(); while (take("or")) and(); depth--;
    }
    private void and() { predicate(); while (take("and")) predicate(); }
    private void predicate() {
        if (take("not")) { if (++depth > 32) throw invalid(); predicate(); depth--; return; }
        arithmetic();
        if (take("is")) { take("not"); require("null"); return; }
        boolean negated = take("not");
        if (take("in")) {
            require("("); arithmetic(); while (take(",")) arithmetic(); require(")");
        } else if (take("between")) { arithmetic(); require("and"); arithmetic(); }
        else if (take("like") || take("ilike")) arithmetic();
        else {
            if (negated) throw invalid();
            if (take("=") || take("==") || take("!=") || take("<>") || take("<=") || take(">=") || take("<") || take(">")) arithmetic();
        }
    }
    private void arithmetic() { product(); while (take("+") || take("-")) product(); }
    private void product() { primary(); while (take("*") || take("/") || take("%")) primary(); }
    private void primary() {
        if (take("+") || take("-")) { if (++depth > 32) throw invalid(); primary(); depth--; return; }
        if (take("(")) { expression(); require(")"); return; }
        if (position >= tokens.size()) throw invalid();
        Token token = tokens.get(position++);
        if (token.kind == 'v') return;
        if (token.kind != 'i' && token.kind != 'q') throw invalid();
        String name = token.text.toLowerCase(Locale.ROOT);
        if (token.kind == 'i' && KEYWORDS.contains(name)) throw invalid();
        if (take("(")) {
            if (token.kind != 'i' || !FUNCTIONS.contains(name)) throw invalid();
            expression(); while (take(",")) expression(); require(")");
        } else if (take(".")) {
            if (!name.equals("src") || position >= tokens.size()) throw invalid();
            Token column = tokens.get(position++);
            if (column.kind != 'i' && column.kind != 'q') throw invalid();
        }
    }
}
