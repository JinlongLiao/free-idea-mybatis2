package io.github.jinlongliao.mybatis.workbench.database;

import org.mybatis.generator.api.intellij.IntellijColumnInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

/**
 * SQL 控制台的前缀候选。基于已浏览的表和当前表的列，不依赖宿主 SQL 插件。
 *
 * @author liaojinlong
 * @since 2026/11/18 16:31
 */
public final class JdbcSqlCompletion {
	/**
	 * 各方言通用的查询和更新关键字。
	 */
	private static final List<String> SQL_KEYWORDS = List.of(
		"SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "INNER JOIN", "ON", "AS", "AND", "OR", "NOT",
		"IS NULL", "IS NOT NULL", "IN", "EXISTS", "GROUP BY", "HAVING", "ORDER BY", "ASC", "DESC",
		"WITH", "INSERT INTO", "VALUES", "UPDATE", "SET", "DELETE FROM", "DISTINCT", "UNION ALL");

	/**
	 * 从编辑器文本识别 T-SQL 方括号标识符，并跳过相邻的引号和注释。连续普通字符和转义对无需回溯。
	 */
	private static final Pattern SQL_SERVER_QUOTED_TOKEN = Pattern.compile(
		"(?s)--[^\\r\\n]*|/\\*.*?\\*/|'(?:''|[^']++)*+'|\"(?:\"\"|[^\"]++)*+\"|\\[((?:[^\\]]++|\\]\\])*+)\\]");

	/** 支持 SQL 控制台中常见的未引用及带引号标识符。 */
	private static final String ALIAS_IDENTIFIER =
		"(?:[\\p{L}\\p{N}_$]++|\"(?:\"\"|[^\"]++)*+\"|`(?:``|[^`]++)*+`|\\[(?:[^\\]]++|\\]\\])*+\\])";

	/** 阻止常见子句、连接及表提示关键字被当作未引用的省略 AS 别名。 */
	private static final String TABLE_CLAUSE_KEYWORD =
		"(?:AS|WHERE|JOIN|INNER|LEFT|RIGHT|FULL|CROSS|NATURAL|OUTER|APPLY|ON|USING|GROUP|HAVING|"
			+ "ORDER|LIMIT|OFFSET|FETCH|FOR|UNION|INTERSECT|EXCEPT|MINUS|WINDOW|QUALIFY|WITH|"
			+ "PARTITION|USE|FORCE|IGNORE|INDEXED|NOT|TABLESAMPLE|CONNECT|START|MODEL|PIVOT|UNPIVOT|OPTION)"
			+ "(?![\\p{L}\\p{N}_$])";

	/** 表引用分支依次捕获模式、表名和别名。 */
	private static final String TABLE_ALIAS_DECLARATION =
		"(?is)\\b(?:FROM|JOIN)\\s+(?:(" + ALIAS_IDENTIFIER + ")\\s*\\.\\s*)?("
			+ ALIAS_IDENTIFIER + ")\\s+(?:AS\\s+|(?!" + TABLE_CLAUSE_KEYWORD + "))(" + ALIAS_IDENTIFIER + ")";

	/** 跳过字符串、注释及引用标识符，并标记括号与语句分隔符。 */
	private static final String SQL_COMPLETION_TOKENS =
		"|--[^\\r\\n]*|/\\*.*?\\*/|'(?:''|[^']++)*+'|[();]|" + ALIAS_IDENTIFIER;

	/** 通用补全使用的别名和边界标记。 */
	private static final Pattern TABLE_ALIAS = Pattern.compile(TABLE_ALIAS_DECLARATION + SQL_COMPLETION_TOKENS);

	/**
	 * PostgreSQL 字符串的 dollar-quote 标签区分大小写，正文可包含换行和 SQL 分隔符。
	 *
	 * @see <a href="https://www.postgresql.org/docs/current/sql-syntax-lexical.html#SQL-SYNTAX-DOLLAR-QUOTING">PostgreSQL Dollar-Quoted String Constants</a>
	 */
	private static final Pattern POSTGRESQL_TABLE_ALIAS = Pattern.compile(TABLE_ALIAS_DECLARATION
		+ "|(?-i:(?<dollar>\\$(?:[\\p{L}_][\\p{L}\\p{N}_]*)?\\$).*?\\k<dollar>)" + SQL_COMPLETION_TOKENS);

	/**
	 * 候选内容由静态方法生成。
	 */
	private JdbcSqlCompletion() {
	}

	/**
	 * 查找某一位置前后连续的 SQL 简单标识符。
	 *
	 * @param sql       当前编辑器全文
	 * @param caret     当前光标位置
	 * @param direction -1 往前查起点，1 往后查终点
	 * @return 标识符的起点或右开端点
	 */
	public static int findIdentifierBoundary(String sql, int caret, int direction) {
		int boundary = caret;
		if (direction < 0) {
			while (boundary > 0 && isIdentifierPart(sql.charAt(boundary - 1))) {
				boundary--;
			}
			return boundary;
		}
		while (boundary < sql.length() && isIdentifierPart(sql.charAt(boundary))) {
			boundary++;
		}
		return boundary;
	}

	/**
	 * 从浏览结果构造最多 50 个候选。点号后优先依据前一段名称寻找列或所属模式的表。
	 *
	 * @param type    当前 SQL 方言
	 * @param sql     当前编辑器全文
	 * @param caret   当前光标位置
	 * @param tables  最近浏览的表和视图
	 * @param table   当前选定表，可为空
	 * @param columns 当前表已读取的列，未选表时传入空列表
	 * @return 按显示顺序排列的可插入 SQL
	 */
	public static List<String> suggestSqlIdentifiers(DbType type, String sql, int caret, List<JdbcTable> tables,
													 JdbcTable table, List<IntellijColumnInfo> columns) {
		int start = findIdentifierBoundary(sql, caret, -1);
		String prefix = sql.substring(start, caret).toLowerCase(Locale.ROOT);
		boolean bracketQualifier = type == DbType.SqlServer && start >= 2 && sql.startsWith("].", start - 2);
		String qualifier = bracketQualifier ? readBracketQualifier(sql, start - 1) : readSqlQualifier(sql, start);
		if (type == DbType.PostgreSQL && (start < 2 || sql.charAt(start - 2) != '"')) {
			qualifier = qualifier.toLowerCase(Locale.ROOT);
		}
		boolean hasQualifier = bracketQualifier || !qualifier.isEmpty();
		List<String> declaration = readTableAlias(type, sql, caret, qualifier);
		var choices = new LinkedHashSet<String>();
		if (!hasQualifier || Objects.nonNull(table)
			&& (declaration.isEmpty() ? matchesSqlName(type, qualifier, table.name()) : matchesTableAlias(type, table, declaration))) {
			for (IntellijColumnInfo column : columns) {
				addMatchingChoice(choices, column.getName(), quoteSqlIdentifier(column.getName(), type), prefix);
			}
		}
		for (JdbcTable item : tables) {
			String scope = tableScope(item);
			if (!hasQualifier || declaration.isEmpty() && matchesSqlName(type, qualifier, scope)
				&& (!bracketQualifier || !scope.isEmpty())) {
				String name = quoteSqlIdentifier(item.name(), type);
				String suggestion = scope.isEmpty() || hasQualifier ? name : quoteSqlIdentifier(scope, type) + "." + name;
				addMatchingChoice(choices, item.name(), suggestion, prefix);
			}
			if (choices.size() >= 50) {
				break;
			}
		}
		if (!hasQualifier) {
			for (String keyword : SQL_KEYWORDS) {
				addMatchingChoice(choices, keyword, keyword, prefix);
			}
			if (type == DbType.MySQL || type == DbType.MariaDB || type == DbType.PostgreSQL
				|| type == DbType.SQLite || type == DbType.H2) {
				addMatchingChoice(choices, "LIMIT", "LIMIT", prefix);
			}
		}
		return List.copyOf(choices);
	}

	/**
	 * 解析光标处的别名或表名，供宿主按需读取唯一匹配表的列。
	 *
	 * @param type 当前数据库方言
	 * @param sql 编辑器全文
	 * @param caret 当前光标位置
	 * @param tables 当前连接已浏览的对象
	 * @param selected 对象树当前选中表，可为空
	 * @return 明确引用的表，无限定符时返回已选表，限定符找不到对应表或存在多个匹配表时为空
	 */
	public static JdbcTable resolveSqlCompletionTable(DbType type, String sql, int caret, List<JdbcTable> tables,
		JdbcTable selected) {
		int start = findIdentifierBoundary(sql, caret, -1);
		boolean bracketQualifier = type == DbType.SqlServer && start >= 2 && sql.startsWith("].", start - 2);
		String qualifier = bracketQualifier ? readBracketQualifier(sql, start - 1) : readSqlQualifier(sql, start);
		if (type == DbType.PostgreSQL && (start < 2 || sql.charAt(start - 2) != '"')) {
			qualifier = qualifier.toLowerCase(Locale.ROOT);
		}
		if (qualifier.isEmpty()) {
			return bracketQualifier ? null : selected;
		}
		List<String> alias = readTableAlias(type, sql, caret, qualifier);
		List<String> reference = alias.isEmpty() ? List.of("", qualifier) : alias;
		JdbcTable resolved = null;
		for (JdbcTable candidate : tables) {
			if (matchesTableAlias(type, candidate, reference)) {
				if (Objects.nonNull(resolved)) {
					return null;
				}
				resolved = candidate;
			}
		}
		if (Objects.nonNull(resolved)) {
			return resolved;
		}
		return Objects.nonNull(selected) && matchesTableAlias(type, selected, reference) ? selected : null;
	}

	/**
	 * 判断别名声明是否指向给定表，包括显式模式及 SQLite 的隐式主库。
	 *
	 * @param type 当前数据库方言
	 * @param table 候选表
	 * @param declaration 模式和表名，空列表表示无法解析
	 * @return 表名与显式模式是否匹配
	 */
	private static boolean matchesTableAlias(DbType type, JdbcTable table, List<String> declaration) {
		return !declaration.isEmpty() && matchesSqlName(type, declaration.get(1), table.name())
			&& (declaration.getFirst().isEmpty() || matchesSqlName(type, declaration.getFirst(),
				type == DbType.SQLite ? JdbcTableSql.sqliteSchema(table) : tableScope(table)));
	}

	/**
	 * 在当前语句中查找最近可见的 FROM/JOIN 别名。内层声明会遮蔽外层同名别名。
	 *
	 * @param type 当前数据库方言，用于识别字符串边界
	 * @param sql 编辑器文本，允许 FROM 子句位于光标之后
	 * @param caret 光标位置，用于确定当前语句
	 * @param qualifier 光标前的限定符
	 * @return 模式和表名，未声明或存在歧义时返回空列表
	 */
	private static List<String> readTableAlias(DbType type, String sql, int caret, String qualifier) {
		if (qualifier.isEmpty()) {
			return List.of();
		}
		var aliases = (type == DbType.PostgreSQL ? POSTGRESQL_TABLE_ALIAS : TABLE_ALIAS).matcher(sql);
		var scopes = new ArrayDeque<Integer>();
		scopes.push(-1);
		List<Integer> cursorScopes = List.of();
		// 一个作用域中的重复声明按歧义处理，即使两次都指向同一张表。
		Map<Integer, List<String>> scopeMatches = new HashMap<>();
		while (aliases.find()) {
			if (cursorScopes.isEmpty() && aliases.start() >= caret) {
				cursorScopes = List.copyOf(scopes);
			}
			if (aliases.end() - aliases.start() == 1) {
				char token = sql.charAt(aliases.start());
				if (token == ';') {
					if (aliases.start() >= caret) {
						break;
					}
					scopes.clear();
					scopes.push(-1);
					scopeMatches.clear();
				} else if (token == '(') {
					scopes.push(aliases.start());
				} else if (token == ')' && scopes.size() > 1) {
					scopes.pop();
				}
			}
			if (Objects.nonNull(aliases.group(3)) && matchesSqlName(type, qualifier, decodeAliasIdentifier(type, aliases.group(3)))) {
				String schema = Objects.isNull(aliases.group(1)) ? "" : decodeAliasIdentifier(type, aliases.group(1));
				scopeMatches.merge(scopes.peek(), List.of(schema, decodeAliasIdentifier(type, aliases.group(2))),
					(previous, current) -> List.of());
			}
		}
		if (cursorScopes.isEmpty()) {
			cursorScopes = List.copyOf(scopes);
		}
		for (int candidateScope : cursorScopes) {
			List<String> match = scopeMatches.get(candidateScope);
			if (Objects.nonNull(match)) {
				return match;
			}
		}
		return List.of();
	}

	/**
	 * 解码别名匹配器已识别出的完整标识符。
	 *
	 * @param type 当前方言，PostgreSQL 将未引用的名称折为小写
	 * @param token SQL 中的标识符
	 * @return 未引用的名称，双写符号还原为单个字符
	 */
	private static String decodeAliasIdentifier(DbType type, String token) {
		return switch (token.charAt(0)) {
			case '[' -> token.substring(1, token.length() - 1).replace("]]", "]");
			case '"', '`' -> {
				String quote = token.substring(0, 1);
				yield token.substring(1, token.length() - 1).replace(quote + quote, quote);
			}
			default -> type == DbType.PostgreSQL ? token.toLowerCase(Locale.ROOT) : token;
		};
	}

	/**
	 * 将已按方言折叠的引用名与 JDBC 元数据名称比较。
	 *
	 * @param type 当前数据库方言
	 * @param reference 已处理引号和大小写的引用名称
	 * @param name 元数据或声明中的对象名
	 * @return 当前方言下两者是否匹配
	 */
	private static boolean matchesSqlName(DbType type, String reference, String name) {
		return type == DbType.PostgreSQL ? reference.equals(name) : reference.equalsIgnoreCase(name);
	}

	/**
	 * 从左到右读取点号前的完整方括号标识符，左括号可作为名称的一部分。
	 *
	 * @param sql 编辑器全文
	 * @param end 限定符的右开端点，即点号位置
	 * @return 解码后的标识符，点号前不在完整标识符边界时返回空字符串
	 */
	private static String readBracketQualifier(String sql, int end) {
		var tokens = SQL_SERVER_QUOTED_TOKEN.matcher(sql).region(0, end);
		while (tokens.find()) {
			if (tokens.end() == end && Objects.nonNull(tokens.group(1))) {
				return tokens.group(1).replace("]]", "]");
			}
		}
		return "";
	}

	/**
	 * @param table 已浏览的表或视图
	 * @return 模式名，缺少时取目录名，两者都缺少时返回空字符串
	 */
	private static String tableScope(JdbcTable table) {
		String schema = Objects.requireNonNullElse(table.schema(), "");
		return schema.isEmpty() ? Objects.requireNonNullElse(table.catalog(), "") : schema;
	}

	/**
	 * 读取点号前一段名称，解开补全自身插入的引号和双写转义。
	 *
	 * @param sql 编辑器全文
	 * @param start 当前待补标识符的起点
	 * @return 原始模式名或表名，无可识别的限定符时为空
	 */
	private static String readSqlQualifier(String sql, int start) {
		int end = start - 1;
		if (end < 1 || sql.charAt(end) != '.') {
			return "";
		}
		char quote = sql.charAt(end - 1);
		if (quote != '"' && quote != '`') {
			return sql.substring(findIdentifierBoundary(sql, end, -1), end);
		}
		for (int index = end - 2; index >= 0; index--) {
			if (sql.charAt(index) != quote) {
				continue;
			}
			if (index > 0 && sql.charAt(index - 1) == quote) {
				index--;
				continue;
			}
			String delimiter = String.valueOf(quote);
			return sql.substring(index + 1, end - 1).replace(delimiter + delimiter, delimiter);
		}
		return "";
	}

	/**
	 * 对通过前缀匹配的候选去重，数量达到上限后停止新增。
	 *
	 * @param choices 已收集候选
	 * @param display 参与前缀匹配的原始名字
	 * @param sql     选择后插入的 SQL 文本
	 * @param prefix  已转为小写的输入前缀
	 */
	private static void addMatchingChoice(LinkedHashSet<String> choices, String display, String sql, String prefix) {
		if (choices.size() < 50 && display.toLowerCase(Locale.ROOT).startsWith(prefix)) {
			choices.add(sql);
		}
	}

	/**
	 * 对数据库给出的标识符引用，保留大小写、空格和保留字名称。
	 *
	 * @param name 原始标识符
	 * @param type 当前方言
	 * @return 可直接插入 SQL 的标识符
	 * @see <a href="https://learn.microsoft.com/sql/relational-databases/databases/database-identifiers">SQL Server 标识符规则</a>
	 */
	private static String quoteSqlIdentifier(String name, DbType type) {
		if (type == DbType.SqlServer) {
			return "[" + name.replace("]", "]]") + "]";
		}
		String quote = type == DbType.MySQL || type == DbType.MariaDB ? "`" : "\"";
		return quote + name.replace(quote, quote + quote) + quote;
	}

	/**
	 * 判断当前字符是否属于 SQL 简单标识符。
	 *
	 * @param character 输入字符
	 * @return 可以构成单词前缀时为 true
	 */
	private static boolean isIdentifierPart(char character) {
		return Character.isLetterOrDigit(character) || character == '_' || character == '$';
	}
}
