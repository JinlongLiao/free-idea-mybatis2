package io.github.jinlongliao.mybatis.workbench.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将表预览中的有界 AND WHERE 与单列 ORDER BY 输入解析成结构化查询选项。
 * 每个条件只允许一个白名单列和固定操作符；值保留为参数文本交由 JDBC 绑定，不生成 SQL 片段。
 *
 * @author liaojinlong
 * @since 2026/11/22 14:02
 */
public final class JdbcTableQueryExpressionParser {
	/** ANSI 双引号、MySQL 反引号、SQL Server 方括号或简单列名；之后仍须匹配当前表元数据。 */
	private static final Pattern COLUMN_IDENTIFIER = Pattern.compile(
		"\"(?:[^\"]|\"\")+\"|`(?:[^`]|``)+`|\\[(?:[^\\]]|\\]\\])+\\]|[\\p{L}_][\\p{L}\\p{N}_$]*");
	/** 单值参数允许的裸 token；运算符、分隔符和空白不能进入 SQL 语法。 */
	private static final Pattern BARE_VALUE = Pattern.compile("[\\p{L}\\p{N}_.$:+/-]+");
	/** SQL 单引号字符串字面量，内部单引号必须双写。 */
	private static final Pattern QUOTED_VALUE = Pattern.compile("'((?:[^']|'')*)'", Pattern.DOTALL);
	/** 受限的前缀匹配运算符；通配符范围由 parsePrefixLikeValue 再次约束。 */
	private static final Pattern PREFIX_LIKE = Pattern.compile("(?i)^LIKE\\s+(.*?)$", Pattern.DOTALL);
	/** IN 运算符外壳；其内部列表由专用扫描器按字符串转义边界解析。 */
	private static final Pattern IN_OPERATOR = Pattern.compile("(?i)^IN\\s*\\((.*)\\)$", Pattern.DOTALL);
	/** WHERE 中唯一支持的比较运算符；较长运算符置前以避免前缀误判。 */
	private static final Pattern COMPARISON = Pattern.compile("^(>=|<=|<>|!=|=|>|<)\\s*(.*?)$", Pattern.DOTALL);
	/** 排序输入允许一个方向关键字。 */
	private static final Pattern SORT_DIRECTION = Pattern.compile("(?i)^(ASC|DESC)$");

	/**
	 * 工具类不允许实例化。
	 */
	private JdbcTableQueryExpressionParser() {
	}

	/**
	 * 解析查询文本；列名必须存在于当前表列清单，比较值永远不会作为 SQL 文本拼接。
	 * WHERE 支持最多 10 个 AND 条件，每个条件为比较、IS NULL、字面前缀 LIKE 或 IN；总绑定值不超过 100。
	 * ORDER BY 仅支持一个列名及可选 ASC/DESC；OR、括号表达式、函数、子查询和分号均拒绝。
	 *
	 * @param whereExpression 单个 WHERE 条件，可为空
	 * @param orderByExpression 单列排序条件，可为空
	 * @param availableColumns 当前表可查询列名
	 * @param message 用于生成当前宿主语言错误文本的资源映射
	 * @return 可直接交由 JDBC 表页服务端查询和参数绑定的选项
	 * @throws IllegalArgumentException 输入不符合受支持语法或列不属于当前表
	 */
	public static JdbcTableQueryOptions parse(String whereExpression, String orderByExpression,
		List<String> availableColumns, Function<String, String> message) {
		JdbcTableQueryOptions filter = parseWhereExpression(whereExpression, availableColumns, message);
		JdbcTableQueryOptions order = parseOrderByExpression(orderByExpression, availableColumns, message);
		return new JdbcTableQueryOptions(filter.filters(), order.sortColumn(), order.sortDescending());
	}

	/**
	 * 将已解析的 WHERE 条件格式化为可重新提交的规范文本，IN 列表值统一以单引号转义表示。
	 *
	 * @param options 结构化查询条件
	 * @return 可由 {@link #parse(String, String, List, Function)} 重新解析的 WHERE 文本
	 */
	public static String formatWhereExpression(JdbcTableQueryOptions options) {
		if (!options.hasFilter()) {
			return "";
		}
		return options.filters().stream().map(JdbcTableQueryExpressionParser::formatCondition)
			.collect(Collectors.joining(" AND "));
	}

	/**
	 * 格式化单个已解析谓词；仅用于可回显的编辑文本，不参与实际 SQL 拼接。
	 *
	 * @param condition 已校验谓词
	 * @return 可由解析器重新解析的谓词文本
	 */
	private static String formatCondition(JdbcTableFilterCondition condition) {
		String column = quoteColumn(condition.column());
		if (condition.operator() == JdbcTableFilterOperator.IS_NULL) {
			return column + " IS NULL";
		}
		if (condition.operator() == JdbcTableFilterOperator.IS_NOT_NULL) {
			return column + " IS NOT NULL";
		}
		if (condition.operator() == JdbcTableFilterOperator.IN) {
			String values = condition.values().stream().map(JdbcTableQueryExpressionParser::quoteValue)
				.collect(Collectors.joining(", "));
			return column + " IN (" + values + ")";
		}
		String operator = condition.operator() == JdbcTableFilterOperator.PREFIX_LIKE
			? "LIKE" : condition.operator().sql();
		return column + " " + operator + " " + quoteValue(condition.value());
	}

	/**
	 * 用 ANSI 双引号格式化列名供界面回显；重新提交时仍须通过当前表元数据校验。
	 *
	 * @param column 元数据中的原始列名
	 * @return 内部双引号已双写的列标识符
	 */
	private static String quoteColumn(String column) {
		return "\"" + column.replace("\"", "\"\"") + "\"";
	}

	/**
	 * 将绑定值格式化为 SQL 字符串字面量供界面回显，不用于拼接实际执行语句。
	 *
	 * @param value JDBC 参数值
	 * @return 内部单引号已双写的单引号文本
	 */
	private static String quoteValue(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	/**
	 * 扫描顶层 AND 分隔符，解析每个 WHERE 条件并把列名还原为元数据原名。
	 *
	 * @param expression 用户输入的 AND 条件文本
	 * @param columns 当前表元数据列名
	 * @param message 当前语言的错误消息解析器
	 * @return WHERE 结构化选项；空文本对应无筛选
	 * @throws IllegalArgumentException 条件语法不支持或列不在当前表中
	 */
	private static JdbcTableQueryOptions parseWhereExpression(String expression, List<String> columns,
		Function<String, String> message) {
		String input = Objects.toString(expression, "").strip();
		if (input.isEmpty()) {
			return JdbcTableQueryOptions.none();
		}
		List<String> expressions = splitAndConditions(input, message);
		if (expressions.size() > JdbcTableQueryOptions.MAX_FILTER_CONDITIONS) {
			throw new IllegalArgumentException(message.apply("table.filter.conditions.bounds"));
		}
		List<JdbcTableFilterCondition> filters = new ArrayList<>(expressions.size());
		int parameterCount = 0;
		for (String condition : expressions) {
			JdbcTableFilterCondition filter = parseSingleWhereCondition(condition, columns, message);
			filters.add(filter);
			parameterCount += filter.operator() == JdbcTableFilterOperator.IN
				? filter.values().size() : filter.operator().requiresValue() ? 1 : 0;
			if (parameterCount > JdbcTableQueryOptions.MAX_FILTER_PARAMETERS) {
				throw new IllegalArgumentException(message.apply("table.filter.parameters.bounds"));
			}
		}
		return new JdbcTableQueryOptions(filters, "", false);
	}

	/**
	 * 解析单个已由引号感知扫描器切分的谓词。
	 *
	 * @param input 单个谓词文本
	 * @param columns 当前表元数据列名
	 * @param message 当前语言的错误消息解析器
	 * @return 结构化条件
	 */
	private static JdbcTableFilterCondition parseSingleWhereCondition(String input, List<String> columns,
		Function<String, String> message) {
		Matcher identifier = COLUMN_IDENTIFIER.matcher(input);
		if (!identifier.lookingAt()) {
			throw new IllegalArgumentException(message.apply("table.filter.expression.invalid"));
		}
		String column = resolveColumn(identifier.group(), columns, "table.filter.invalid.column", message);
		String condition = input.substring(identifier.end()).strip();
		if (condition.matches("(?i)^IS\\s+NULL$")) {
			return new JdbcTableFilterCondition(column, JdbcTableFilterOperator.IS_NULL, "", List.of());
		}
		if (condition.matches("(?i)^IS\\s+NOT\\s+NULL$")) {
			return new JdbcTableFilterCondition(column, JdbcTableFilterOperator.IS_NOT_NULL, "", List.of());
		}
		Matcher prefixLike = PREFIX_LIKE.matcher(condition);
		if (prefixLike.matches()) {
			String value = parsePrefixLikeValue(prefixLike.group(1), message);
			return new JdbcTableFilterCondition(column, JdbcTableFilterOperator.PREFIX_LIKE, value, List.of());
		}
		Matcher in = IN_OPERATOR.matcher(condition);
		if (in.matches()) {
			List<String> values = parseInValues(in.group(1), message);
			return new JdbcTableFilterCondition(column, JdbcTableFilterOperator.IN, "", values);
		}
		Matcher comparison = COMPARISON.matcher(condition);
		if (!comparison.matches()) {
			throw new IllegalArgumentException(message.apply("table.filter.expression.invalid"));
		}
		String value = parseBoundValue(comparison.group(2), message);
		JdbcTableFilterOperator operator = switch (comparison.group(1)) {
			case "=" -> JdbcTableFilterOperator.EQUALS;
			case "!=", "<>" -> JdbcTableFilterOperator.NOT_EQUALS;
			case ">" -> JdbcTableFilterOperator.GREATER_THAN;
			case ">=" -> JdbcTableFilterOperator.GREATER_THAN_OR_EQUALS;
			case "<" -> JdbcTableFilterOperator.LESS_THAN;
			case "<=" -> JdbcTableFilterOperator.LESS_THAN_OR_EQUALS;
			default -> throw new IllegalArgumentException(message.apply("table.filter.expression.invalid"));
		};
		return new JdbcTableFilterCondition(column, operator, value, List.of());
	}

	/**
	 * 仅在 SQL 引号之外按独立 AND 关键字切分；双写引号作为转义符消费，避免拆开字符串或引用列名。
	 * 括号、注释、分号等仍由单条件解析器拒绝，不通过此扫描器转成可执行表达式。
	 *
	 * @param input 已去除首尾空白的 WHERE 文本
	 * @param message 当前语言的错误消息解析器
	 * @return 有序谓词文本
	 */
	private static List<String> splitAndConditions(String input, Function<String, String> message) {
		List<String> conditions = new ArrayList<>();
		int start = 0;
		char quote = 0;
		for (int index = 0; index < input.length(); index++) {
			char current = input.charAt(index);
			if (quote != 0) {
				char closing = quote == '[' ? ']' : quote;
				if (current == closing) {
					if (index + 1 < input.length() && input.charAt(index + 1) == closing) {
						index++;
					} else {
						quote = 0;
					}
				}
				continue;
			}
			if (current == '\'' || current == '"' || current == '`' || current == '[') {
				quote = current;
				continue;
			}
			if ((current == 'A' || current == 'a') && index + 3 <= input.length()
				&& input.regionMatches(true, index, "AND", 0, 3)
				&& (index == 0 || Character.isWhitespace(input.charAt(index - 1)))
				&& (index + 3 == input.length() || Character.isWhitespace(input.charAt(index + 3)))) {
				String condition = input.substring(start, index).strip();
				if (condition.isEmpty()) {
					throw new IllegalArgumentException(message.apply("table.filter.expression.invalid"));
				}
				conditions.add(condition);
				if (conditions.size() >= JdbcTableQueryOptions.MAX_FILTER_CONDITIONS) {
					throw new IllegalArgumentException(message.apply("table.filter.conditions.bounds"));
				}
				start = index + 3;
				index += 2;
			}
		}
		if (quote != 0) {
			throw new IllegalArgumentException(message.apply("table.filter.expression.invalid"));
		}
		String last = input.substring(start).strip();
		if (last.isEmpty()) {
			throw new IllegalArgumentException(message.apply("table.filter.expression.invalid"));
		}
		conditions.add(last);
		return conditions;
	}

	/**
	 * 解析单列排序并拒绝多列、表达式或未注册的方向。
	 *
	 * @param expression 用户输入的 ORDER BY 文本
	 * @param columns 当前表元数据列名
	 * @param message 当前语言的错误消息解析器
	 * @return ORDER BY 结构化选项；空文本对应主键默认排序
	 * @throws IllegalArgumentException 排序语法不支持或列不在当前表中
	 */
	private static JdbcTableQueryOptions parseOrderByExpression(String expression, List<String> columns,
		Function<String, String> message) {
		String input = Objects.toString(expression, "").strip();
		if (input.isEmpty()) {
			return JdbcTableQueryOptions.none();
		}
		Matcher identifier = COLUMN_IDENTIFIER.matcher(input);
		if (!identifier.lookingAt()) {
			throw new IllegalArgumentException(message.apply("table.sort.expression.invalid"));
		}
		String column = resolveColumn(identifier.group(), columns, "table.sort.invalid.column", message);
		String direction = input.substring(identifier.end()).strip();
		if (direction.isEmpty()) {
			return new JdbcTableQueryOptions("", JdbcTableFilterOperator.EQUALS, "", column, false);
		}
		Matcher sortDirection = SORT_DIRECTION.matcher(direction);
		if (!sortDirection.matches()) {
			throw new IllegalArgumentException(message.apply("table.sort.expression.invalid"));
		}
		return new JdbcTableQueryOptions("", JdbcTableFilterOperator.EQUALS, "", column,
			"DESC".equalsIgnoreCase(sortDirection.group(1)));
	}

	/**
	 * 匹配当前表列并拒绝未知或有歧义的大小写折叠名称。
	 * ANSI 双引号、MySQL 反引号及 SQL Server 方括号列名按引用名精确匹配；未引用名称优先精确匹配，
	 * 否则只接受唯一的不区分大小写匹配。引用符内的分隔符按对应数据库规则双写转义。
	 *
	 * @param token 用户输入的列标识符
	 * @param columns 当前表元数据列名
	 * @param errorKey 未匹配或匹配歧义时使用的资源键
	 * @param message 当前语言的错误消息解析器
	 * @return 与元数据完全一致的列名
	 * @throws IllegalArgumentException 列不存在或大小写折叠后有歧义
	 */
	private static String resolveColumn(String token, List<String> columns, String errorKey,
		Function<String, String> message) {
		boolean quoted = token.startsWith("\"") || token.startsWith("`") || token.startsWith("[");
		String name = token;
		if (token.startsWith("\"")) {
			name = token.substring(1, token.length() - 1).replace("\"\"", "\"");
		} else if (token.startsWith("`")) {
			name = token.substring(1, token.length() - 1).replace("``", "`");
		} else if (token.startsWith("[")) {
			name = token.substring(1, token.length() - 1).replace("]]", "]");
		}
		for (String column : columns) {
			if (column.equals(name)) {
				return column;
			}
		}
		if (!quoted) {
			String matched = null;
			for (String column : columns) {
				if (column.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
					if (Objects.nonNull(matched)) {
						throw new IllegalArgumentException(message.apply(errorKey));
					}
					matched = column;
				}
			}
			if (Objects.nonNull(matched)) {
				return matched;
			}
		}
		throw new IllegalArgumentException(message.apply(errorKey));
	}

	/**
	 * 将输入值收敛为一个 JDBC 绑定参数，拒绝可被误认为 SQL 子句的复合文本。
	 * 带单引号值按 SQL 双写规则解码；裸值仅接受不含空白和 SQL 结构符的单 token。
	 *
	 * @param value 比较操作符右侧的用户输入
	 * @param message 当前语言的错误消息解析器
	 * @return 交由 JDBC 参数绑定的原始值
	 * @throws IllegalArgumentException 值为空、NULL 字面量或不符合单值语法
	 */
	private static String parseBoundValue(String value, Function<String, String> message) {
		String input = Objects.toString(value, "").strip();
		if (input.equalsIgnoreCase("NULL")) {
			throw new IllegalArgumentException(message.apply("table.filter.null.use.is"));
		}
		Matcher quoted = QUOTED_VALUE.matcher(input);
		if (quoted.matches()) {
			return quoted.group(1).replace("''", "'");
		}
		if (BARE_VALUE.matcher(input).matches()) {
			return input;
		}
		throw new IllegalArgumentException(message.apply("table.filter.expression.value.invalid"));
	}

	/**
	 * 接受一个字面前缀及恰好位于末尾的 `%`，以避免左模糊、全模糊或额外通配符扩大扫描范围。
	 * `%`、`_`、`[` 和反斜杠不允许出现在字面前缀中，确保 SQL Server 字符类及各数据库的转义默认值不会改变含义。
	 *
	 * @param value LIKE 右侧的用户输入
	 * @param message 当前语言的错误消息解析器
	 * @return 作为 JDBC 参数绑定的前缀模式
	 * @throws IllegalArgumentException 值不是单引号包围且恰有一个末尾通配符的前缀模式
	 */
	private static String parsePrefixLikeValue(String value, Function<String, String> message) {
		String input = Objects.toString(value, "").strip();
		Matcher quoted = QUOTED_VALUE.matcher(input);
		if (!quoted.matches()) {
			throw new IllegalArgumentException(message.apply("table.filter.like.prefix.only"));
		}
		String pattern = quoted.group(1).replace("''", "'");
		if (pattern.length() < 2 || !pattern.endsWith("%")) {
			throw new IllegalArgumentException(message.apply("table.filter.like.prefix.only"));
		}
		String prefix = pattern.substring(0, pattern.length() - 1);
		if (prefix.contains("%") || prefix.contains("_") || prefix.contains("[") || prefix.contains("\\")) {
			throw new IllegalArgumentException(message.apply("table.filter.like.prefix.only"));
		}
		return pattern;
	}

	/**
	 * 逐项扫描 IN 列表，支持以 SQL 单引号包围的值和简单裸值；拒绝缺失项、裸 NULL 及未闭合字符串，允许明确写出的空字符串字面量。
	 * 列表必须保留输入顺序以参与查询快照相等性与分页条件复用，返回值为不可变副本。
	 *
	 * @param expression IN 圆括号内的列表文本
	 * @param message 当前语言的错误消息解析器
	 * @return 1 至 {@value JdbcTableQueryOptions#MAX_IN_VALUES} 项绑定值
	 * @throws IllegalArgumentException 列表格式、值内容或数量超出当前安全边界
	 */
	private static List<String> parseInValues(String expression, Function<String, String> message) {
		List<String> values = new ArrayList<>();
		int cursor = 0;
		while (cursor < expression.length()) {
			while (cursor < expression.length() && Character.isWhitespace(expression.charAt(cursor))) {
				cursor++;
			}
			if (cursor == expression.length() || expression.charAt(cursor) == ',') {
				throw new IllegalArgumentException(message.apply("table.filter.in.invalid"));
			}
			String value;
			if (expression.charAt(cursor) == '\'') {
				StringBuilder quotedValue = new StringBuilder();
				cursor++;
				boolean closed = false;
				while (cursor < expression.length()) {
					char current = expression.charAt(cursor);
					if (current == '\'') {
						if (cursor + 1 < expression.length() && expression.charAt(cursor + 1) == '\'') {
							quotedValue.append('\'');
							cursor += 2;
						} else {
							cursor++;
							closed = true;
							break;
						}
					} else {
						quotedValue.append(current);
						cursor++;
					}
				}
				if (!closed) {
					throw new IllegalArgumentException(message.apply("table.filter.in.invalid"));
				}
				value = quotedValue.toString();
			} else {
				int start = cursor;
				while (cursor < expression.length() && expression.charAt(cursor) != ',') {
					cursor++;
				}
				value = parseBoundValue(expression.substring(start, cursor), message);
			}
			values.add(value);
			if (values.size() > JdbcTableQueryOptions.MAX_IN_VALUES) {
				throw new IllegalArgumentException(message.apply("table.filter.in.bounds"));
			}
			while (cursor < expression.length() && Character.isWhitespace(expression.charAt(cursor))) {
				cursor++;
			}
			if (cursor < expression.length()) {
				if (expression.charAt(cursor) != ',') {
					throw new IllegalArgumentException(message.apply("table.filter.in.invalid"));
				}
				cursor++;
				if (cursor == expression.length()) {
					throw new IllegalArgumentException(message.apply("table.filter.in.invalid"));
				}
			}
		}
		if (values.isEmpty()) {
			throw new IllegalArgumentException(message.apply("table.filter.in.invalid"));
		}
		return List.copyOf(values);
	}
}
