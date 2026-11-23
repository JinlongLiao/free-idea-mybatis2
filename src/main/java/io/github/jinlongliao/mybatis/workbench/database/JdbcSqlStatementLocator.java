package io.github.jinlongliao.mybatis.workbench.database;

import io.github.jinlongliao.mybatis.workbench.model.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 从 SQL 控制台脚本文本中定位光标所在的单条 JDBC 语句；词法不明确或包含客户端脚本边界时拒绝定位。
 *
 * @author liaojinlong
 * @since 2026/11/22 18:36
 */
public final class JdbcSqlStatementLocator {
	/** 单次扫描的最大 SQL 字符数，避免无界 UI 文本扫描。 */
	private static final int MAX_SQL_LENGTH = 1_000_000;
	/** 单次扫描的最大语句片段数，包含空片段。 */
	private static final int MAX_STATEMENT_SEGMENTS = 1_000;

	/** 禁止实例化，所有定位状态仅存在于一次静态调用中。 */
	private JdbcSqlStatementLocator() {
	}

	/**
	 * 在有界词法扫描后返回光标所在 SQL 单句，不含句末分号。
	 * 光标落在分号上归前句；语句外的空白和独立注释不自动归属任何语句，避免误执行相邻 DML。
	 *
	 * @param type 当前 JDBC 方言
	 * @param script SQL 控制台完整文本
	 * @param caret 光标偏移，范围为 0 至文本长度
	 * @return 光标位于语句正文时返回该语句；纯空白输入返回空字符串
	 * @throws IllegalArgumentException 偏移无效、文本过长、词法不闭合、脚本含客户端分隔指令或光标不在明确语句正文内
	 */
	public static String locateAtCaret(DbType type, String script, int caret) {
		return scanScript(type, script, caret, false);
	}

	/**
	 * 校验并返回恰好一条可解释 SQL，移除前置注释以便方言的 EXPLAIN 白名单读取首关键字。
	 *
	 * @param type 当前 JDBC 方言
	 * @param script SQL 文本，可含一个可选句末分号
	 * @return 唯一 SQL 语句；前置注释、语句外空白及句末分号不返回
	 * @throws IllegalArgumentException 存在多条语句、客户端指令、未闭合词法结构或空脚本
	 */
	public static String singleStatementForExplain(DbType type, String script) {
		return scanScript(type, script, -1, true);
	}

	/**
	 * 对脚本做一次有界词法扫描，供光标定位和 EXPLAIN 单句校验共用。
	 *
	 * @param type 当前 JDBC 方言
	 * @param script SQL 控制台文本
	 * @param caret 光标偏移；EXPLAIN 校验模式传 -1
	 * @param requireSingle 是否要求恰好一条 SQL 语句
	 * @return 定位语句或唯一可解释语句
	 */
	private static String scanScript(DbType type, String script, int caret, boolean requireSingle) {
		String sql = Objects.toString(script, "");
		if (Objects.isNull(type) || (requireSingle ? caret != -1 : caret < 0 || caret > sql.length())
			|| sql.length() > MAX_SQL_LENGTH) {
			throw new IllegalArgumentException("SQL text or caret is outside the supported range.");
		}
		if (!requireSingle && sql.isBlank()) {
			return "";
		}
		List<Integer> starts = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		List<Integer> codeStarts = new ArrayList<>();
		List<Integer> codeEnds = new ArrayList<>();
		List<Integer> delimiters = new ArrayList<>();
		int segmentStart = 0;
		int codeStart = -1;
		int codeEnd = -1;
		char quote = 0;
		char oracleQuoteEnd = 0;
		String dollarTag = null;
		int blockDepth = 0;
		boolean lineComment = false;
		boolean proceduralToken = false;
		boolean createToken = false;
		boolean triggerToken = false;
		for (int index = 0; index < sql.length(); index++) {
			char current = sql.charAt(index);
			if (lineComment) {
				if (current == '\n' || current == '\r') {
					lineComment = false;
				}
				continue;
			}
			if (blockDepth > 0) {
				if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*'
					&& supportsNestedBlockComments(type)) {
					blockDepth++;
					index++;
				} else if (current == '*' && index + 1 < sql.length() && sql.charAt(index + 1) == '/') {
					blockDepth--;
					index++;
				}
				continue;
			}
			if (Objects.nonNull(dollarTag)) {
				if (sql.startsWith(dollarTag, index)) {
					index += dollarTag.length() - 1;
					dollarTag = null;
				}
				continue;
			}
			if (quote != 0) {
				char closing = quote == '[' ? ']' : quote;
				if (current == '\\' && index + 1 < sql.length() && sql.charAt(index + 1) == closing
					&& (quote == '\'' || (type == DbType.MySQL || type == DbType.MariaDB)
						&& (quote == '"' || quote == '`'))) {
					throw new IllegalArgumentException("Backslash escaping in string literals is dialect-mode dependent.");
				}
				if (current == closing) {
					if (index + 1 < sql.length() && sql.charAt(index + 1) == closing) {
					index++;
				} else {
					quote = 0;
				}
				}
				codeEnd = index + 1;
				continue;
			}
			if (oracleQuoteEnd != 0) {
				if (current == oracleQuoteEnd && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
					index++;
					oracleQuoteEnd = 0;
				}
				codeEnd = index + 1;
				continue;
			}

			if (isClientDirective(type, sql, index)) {
				throw new IllegalArgumentException("Client-side script delimiters are not supported.");
			}
			if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-'
				&& isDashComment(type, sql, index)) {
				lineComment = true;
				index++;
				continue;
			}
			if ((type == DbType.MySQL || type == DbType.MariaDB) && current == '#') {
				lineComment = true;
				continue;
			}
			if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
				if (isExecutableBlockComment(type, sql, index)) {
					throw new IllegalArgumentException("Executable comments are not supported for statement inference.");
				}
				blockDepth = 1;
				index++;
				continue;
			}
			if (type == DbType.Oracle && isOracleAlternativeQuote(sql, index)) {
				char opener = sql.charAt(index + 2);
				oracleQuoteEnd = switch (opener) {
					case '[' -> ']';
					case '(' -> ')';
					case '{' -> '}';
					case '<' -> '>';
					default -> opener;
				};
				if (codeStart < 0) {
					codeStart = index;
				}
				codeEnd = index + 3;
				index += 2;
				continue;
			}
			if (type == DbType.PostgreSQL && current == '$') {
				String tag = readDollarTag(sql, index);
				if (Objects.nonNull(tag)) {
					if (codeStart < 0) {
						codeStart = index;
					}
					codeEnd = index + tag.length();
					dollarTag = tag;
					index += tag.length() - 1;
					continue;
				}
			}
			if (current == '\'' || current == '"' || current == '`'
				|| type == DbType.SqlServer && current == '[') {
				if (codeStart < 0) {
					codeStart = index;
				}
				codeEnd = index + 1;
				quote = current;
				continue;
			}
			if (current == ';') {
				addSegment(starts, ends, codeStarts, codeEnds, delimiters, segmentStart, index, codeStart,
					codeEnd, index);
				segmentStart = index + 1;
				codeStart = -1;
				codeEnd = -1;
				if (starts.size() >= MAX_STATEMENT_SEGMENTS) {
					throw new IllegalArgumentException("Too many SQL statement segments.");
				}
				index++;
				while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
					index++;
				}
				index--;
				continue;
			}
			if (!Character.isWhitespace(current)) {
				if (codeStart < 0) {
					codeStart = index;
				}
				codeEnd = index + 1;
				if (Character.isLetter(current) || current == '_') {
					int wordEnd = index + 1;
					while (wordEnd < sql.length() && isWordPart(sql.charAt(wordEnd))) {
						wordEnd++;
					}
					String word = sql.substring(index, wordEnd).toUpperCase(Locale.ROOT);
					proceduralToken |= word.equals("BEGIN") || word.equals("DECLARE");
					createToken |= word.equals("CREATE");
					triggerToken |= word.equals("TRIGGER");
					index = wordEnd - 1;
					codeEnd = wordEnd;
				}
			}
		}
		if (quote != 0 || oracleQuoteEnd != 0 || Objects.nonNull(dollarTag) || blockDepth > 0) {
			throw new IllegalArgumentException("SQL contains an unterminated quoted value or comment.");
		}
		if (proceduralToken && !delimiters.isEmpty() || createToken && triggerToken && !delimiters.isEmpty()) {
			throw new IllegalArgumentException("Procedural SQL scripts require an explicit selection.");
		}
		addSegment(starts, ends, codeStarts, codeEnds, delimiters, segmentStart, sql.length(), codeStart,
			codeEnd, -1);
		if (starts.size() > MAX_STATEMENT_SEGMENTS) {
			throw new IllegalArgumentException("Too many SQL statement segments.");
		}
		if (requireSingle) {
			int statementIndex = -1;
			int delimiterCount = 0;
			int delimiterSegment = -1;
			for (int index = 0; index < codeStarts.size(); index++) {
				if (codeStarts.get(index) >= 0) {
					if (statementIndex >= 0) {
						throw new IllegalArgumentException("EXPLAIN accepts exactly one SQL statement.");
					}
					statementIndex = index;
				}
				if (delimiters.get(index) >= 0) {
					delimiterCount++;
					delimiterSegment = index;
				}
			}
			if (statementIndex < 0 || delimiterCount > 1
				|| delimiterCount == 1 && statementIndex != delimiterSegment) {
				throw new IllegalArgumentException("EXPLAIN accepts exactly one SQL statement.");
			}
			return sql.substring(codeStarts.get(statementIndex), ends.get(statementIndex)).strip();
		}
		for (int index = 0; index < starts.size(); index++) {
			int bodyStart = codeStarts.get(index);
			int bodyEnd = codeEnds.get(index);
			if (bodyStart >= 0 && caret >= bodyStart && caret <= bodyEnd) {
				return sql.substring(starts.get(index), ends.get(index)).strip();
			}
			if (caret >= starts.get(index) && caret <= ends.get(index)) {
				if (delimiters.get(index) >= 0 && caret == delimiters.get(index)) {
					if (bodyStart >= 0) {
						return sql.substring(starts.get(index), ends.get(index)).strip();
					}
					throw new IllegalArgumentException("The caret is on an empty SQL statement delimiter.");
				}
				int gapStart = starts.get(index);
				int gapEnd = ends.get(index);
				int previousStatement = findPreviousStatement(codeStarts, index);
				int nextStatement = findNextStatement(codeStarts, index);
				if (bodyStart >= 0 && caret < bodyStart) {
					gapEnd = bodyStart;
					nextStatement = index;
				} else if (bodyStart >= 0 && caret > bodyEnd) {
					gapStart = bodyEnd;
					previousStatement = index;
				} else if (bodyStart < 0) {
					nextStatement = findNextStatement(codeStarts, index);
				}
				if (isWhitespaceOnly(sql, gapStart, gapEnd)
					&& (previousStatement < 0 || nextStatement < 0)) {
					int adjacent = previousStatement >= 0 ? previousStatement : nextStatement;
					if (adjacent >= 0) {
						return sql.substring(starts.get(adjacent), ends.get(adjacent)).strip();
					}
				}
				throw new IllegalArgumentException("Place the caret inside one SQL statement, not in an ambiguous gap.");
			}
		}
		throw new IllegalArgumentException("Place the caret inside one SQL statement, not between statements.");
	}

	/**
	 * 保存一段语句的原始范围和正文范围；正文范围不含注释与语句外空白，用于拒绝歧义光标位置。
	 *
	 * @param starts 语句片段起点列表
	 * @param ends 语句片段右开端点列表
	 * @param codeStarts 首个 SQL token 起点列表
	 * @param codeEnds 最后一个 SQL token 的右开端点列表
	 * @param delimiters 句末分号位置，无分号时为 -1
	 * @param start 当前片段起点
	 * @param end 当前片段右开端点
	 * @param codeStart 当前片段首个正文字符
	 * @param codeEnd 当前片段正文右开端点
	 * @param delimiter 当前片段后的分号位置
	 */
	private static void addSegment(List<Integer> starts, List<Integer> ends, List<Integer> codeStarts,
		List<Integer> codeEnds, List<Integer> delimiters, int start, int end, int codeStart, int codeEnd,
		int delimiter) {
		starts.add(start);
		ends.add(end);
		codeStarts.add(codeStart);
		codeEnds.add(codeEnd);
		delimiters.add(delimiter);
	}

	/**
	 * 找到光标间隙前最近的一条有 SQL 正文的片段。
	 *
	 * @param codeStarts 各片段正文起点
	 * @param beforeIndex 当前片段索引
	 * @return 前一条语句片段索引；不存在时为 -1
	 */
	private static int findPreviousStatement(List<Integer> codeStarts, int beforeIndex) {
		for (int index = beforeIndex - 1; index >= 0; index--) {
			if (codeStarts.get(index) >= 0) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * 找到光标间隙后最近的一条有 SQL 正文的片段。
	 *
	 * @param codeStarts 各片段正文起点
	 * @param afterIndex 当前片段索引
	 * @return 后一条语句片段索引；不存在时为 -1
	 */
	private static int findNextStatement(List<Integer> codeStarts, int afterIndex) {
		for (int index = afterIndex + 1; index < codeStarts.size(); index++) {
			if (codeStarts.get(index) >= 0) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * 判断注释边界外的脚本间隙是否完全由空白组成；注释内容不自动归属相邻语句。
	 *
	 * @param sql 完整脚本
	 * @param start 间隙起点
	 * @param end 间隙右开端点
	 * @return 间隙为空或仅含空白字符时为 true
	 */
	private static boolean isWhitespaceOnly(String sql, int start, int end) {
		for (int index = start; index < end; index++) {
			if (!Character.isWhitespace(sql.charAt(index))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 判断当前行是否包含 MySQL DELIMITER 或 SQL Server GO 客户端指令；这些不是 JDBC SQL 语句分隔符。
	 *
	 * @param type 当前 JDBC 方言
	 * @param sql 完整脚本
	 * @param index 当前扫描位置
	 * @return 当前索引位于受支持的脚本指令行起始处时为 true
	 */
	private static boolean isClientDirective(DbType type, String sql, int index) {
		if (Character.isWhitespace(sql.charAt(index))
			|| index > 0 && !Character.isWhitespace(sql.charAt(index - 1))) {
			return false;
		}
		int lineStart = index;
		while (lineStart > 0 && Character.isWhitespace(sql.charAt(lineStart - 1))
			&& sql.charAt(lineStart - 1) != '\n' && sql.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}
		if (lineStart > 0 && sql.charAt(lineStart - 1) != '\n' && sql.charAt(lineStart - 1) != '\r') {
			return false;
		}
		int lineEnd = index;
		while (lineEnd < sql.length() && sql.charAt(lineEnd) != '\n' && sql.charAt(lineEnd) != '\r') {
			lineEnd++;
		}
		String line = sql.substring(index, lineEnd).strip();
		if ((type == DbType.MySQL || type == DbType.MariaDB) && line.regionMatches(true, 0, "DELIMITER", 0, 9)
			&& (line.length() == 9 || Character.isWhitespace(line.charAt(9)))) {
			return true;
		}
		if (type == DbType.SqlServer && line.length() >= 2 && line.regionMatches(true, 0, "GO", 0, 2)
			&& (line.length() == 2 || Character.isWhitespace(line.charAt(2)))) {
			String suffix = line.substring(2).strip();
			return suffix.isEmpty() || suffix.matches("[0-9]+") || suffix.startsWith("--");
		}
		return false;
	}

	/**
	 * 识别 PostgreSQL dollar-quoted 字符串起始标签；正文由精确同标签闭合。
	 *
	 * @param sql 完整脚本
	 * @param start 美元符位置
	 * @return dollar quote 标签；当前字符不是合法标签起点时返回 null
	 */
	private static String readDollarTag(String sql, int start) {
		int cursor = start + 1;
		if (cursor < sql.length() && sql.charAt(cursor) == '$') {
			return "$$";
		}
		if (cursor >= sql.length() || !(Character.isLetter(sql.charAt(cursor)) || sql.charAt(cursor) == '_')) {
			return null;
		}
		cursor++;
		while (cursor < sql.length() && (Character.isLetterOrDigit(sql.charAt(cursor)) || sql.charAt(cursor) == '_')) {
			cursor++;
		}
		return cursor < sql.length() && sql.charAt(cursor) == '$' ? sql.substring(start, cursor + 1) : null;
	}

	/**
	 * 判断 Oracle alternative quoting 起始子句 q'X。
	 *
	 * @param sql 完整脚本
	 * @param index 当前字符索引
	 * @return 当前字符开始的是 q quote 时为 true
	 */
	private static boolean isOracleAlternativeQuote(String sql, int index) {
		return index + 2 < sql.length() && (sql.charAt(index) == 'q' || sql.charAt(index) == 'Q')
			&& sql.charAt(index + 1) == '\'' && (index == 0 || !isWordPart(sql.charAt(index - 1)));
	}

	/**
	 * 判断当前方言是否允许块注释嵌套。
	 *
	 * @param type 当前 JDBC 方言
	 * @return PostgreSQL 和 SQL Server 支持的嵌套注释扫描
	 */
	private static boolean supportsNestedBlockComments(DbType type) {
		return type == DbType.PostgreSQL || type == DbType.SqlServer;
	}

	/**
	 * 识别 MySQL/MariaDB 可执行注释；其正文可能含 SQL 分隔符，无法当普通注释跳过。
	 *
	 * @param type 当前 JDBC 方言
	 * @param sql 完整脚本
	 * @param index 块注释起点
	 * @return 该方言会执行的注释体时为 true
	 * @see <a href="https://dev.mysql.com/doc/refman/8.4/en/comments.html">MySQL 8.4 comments</a>
	 * @see <a href="https://mariadb.com/docs/server/reference/sql-statements/comment-syntax">MariaDB comment syntax</a>
	 */
	private static boolean isExecutableBlockComment(DbType type, String sql, int index) {
		if (type != DbType.MySQL && type != DbType.MariaDB) {
			return false;
		}
		if (index + 2 < sql.length() && sql.charAt(index + 2) == '!') {
			return true;
		}
		return type == DbType.MariaDB && index + 3 < sql.length()
			&& (sql.charAt(index + 2) == 'M' || sql.charAt(index + 2) == 'm') && sql.charAt(index + 3) == '!';
	}

	/**
	 * 识别 SQL 标准行注释，并遵守 MySQL/MariaDB 对双短横线后空白的要求。
	 *
	 * @param type 当前 JDBC 方言
	 * @param sql 完整脚本
	 * @param index 第一个短横线索引
	 * @return 是否按行注释处理
	 */
	private static boolean isDashComment(DbType type, String sql, int index) {
		if (type != DbType.MySQL && type != DbType.MariaDB) {
			return true;
		}
		int next = index + 2;
		return next >= sql.length() || Character.isWhitespace(sql.charAt(next)) || Character.isISOControl(sql.charAt(next));
	}

	/**
	 * 判断 SQL 关键字 token 字符，避免把复合标识符中的 BEGIN 或 TRIGGER 误识别为脚本结构。
	 *
	 * @param value 当前字符
	 * @return 字母、数字、下划线或美元符时为 true
	 */
	private static boolean isWordPart(char value) {
		return Character.isLetterOrDigit(value) || value == '_' || value == '$';
	}
}
