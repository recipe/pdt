/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Zend Technologies
 *******************************************************************************/
package org.eclipse.php.internal.core.format;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.php.internal.core.Logger;
import org.eclipse.php.internal.core.documentModel.parser.regions.IPhpScriptRegion;
import org.eclipse.php.internal.core.documentModel.parser.regions.PHPRegionTypes;
import org.eclipse.php.internal.core.documentModel.partitioner.PHPPartitionTypes;
import org.eclipse.wst.sse.core.internal.provisional.text.IStructuredDocument;
import org.eclipse.wst.sse.core.internal.provisional.text.IStructuredDocumentRegion;
import org.eclipse.wst.sse.core.internal.provisional.text.ITextRegion;
import org.eclipse.wst.sse.core.internal.provisional.text.ITextRegionContainer;
import org.eclipse.wst.sse.core.internal.text.rules.SimpleStructuredRegion;

public class PhpIndentationFormatter {

	private final IIndentationStrategy defaultIndentationStrategy;
	private final IIndentationStrategy curlyCloseIndentationStrategy;
	private final IIndentationStrategy caseDefaultIndentationStrategy;
	private final IIndentationStrategy commentIndentationStrategy;
	private final IIndentationStrategy phpCloseTagIndentationStrategy;

	private final int length;
	private final int start;

	private static final byte CHAR_TAB = '\t';
	private static final byte CHAR_SPACE = ' ';

	private final StringBuffer resultBuffer = new StringBuffer();
	private boolean isInHeredoc;
	private Set<Integer> ignoreLines = new HashSet<Integer>();

	public PhpIndentationFormatter(int start, int length,
			IndentationObject indentationObject) {
		this.start = start;
		this.length = length;
		this.defaultIndentationStrategy = new DefaultIndentationStrategy(
				indentationObject);
		this.curlyCloseIndentationStrategy = new CurlyCloseIndentationStrategy();
		this.caseDefaultIndentationStrategy = new CaseDefaultIndentationStrategy(
				indentationObject);
		this.commentIndentationStrategy = new CommentIndentationStrategy();
		this.phpCloseTagIndentationStrategy = new PHPCloseTagIndentationStrategy();
	}

	public void format(IStructuredDocumentRegion sdRegion) {
		assert sdRegion != null;

		// resolce formatter range
		int regionStart = sdRegion.getStartOffset();
		int regionEnd = sdRegion.getEnd();

		int formatRequestStart = getStart();
		int formatRequestEnd = formatRequestStart + getLength();

		int startFormat = Math.max(formatRequestStart, regionStart);
		int endFormat = Math.min(formatRequestEnd, regionEnd);

		// calculate lines
		IStructuredDocument document = sdRegion.getParentDocument();
		int lineIndex = document.getLineOfOffset(startFormat);
		int endLineIndex = document.getLineOfOffset(endFormat);

		// TODO get token of each line then insert line seporator after { and
		// after } if there is no line seporator
		// format each line
		for (; lineIndex <= endLineIndex; lineIndex++) {
			formatLine(document, lineIndex);
		}

	}

	/**
	 * formats a PHP line according to the strategies and formatting conventions
	 * 
	 * @param document
	 * @param lineNumber
	 *            TODO: we should invoke document.replace() one and not twice!
	 */
	private void formatLine(IStructuredDocument document, int lineNumber) {
		resultBuffer.setLength(0);

		try {

			// get original line information
			final IRegion originalLineInfo = document
					.getLineInformation(lineNumber);
			final int orginalLineStart = originalLineInfo.getOffset();
			final int originalLineLength = originalLineInfo.getLength();

			// fast resolving of empty line
			if (originalLineLength == 0)
				return;

			// get formatted line information
			final String lineText = document.get(orginalLineStart,
					originalLineLength);
			final IRegion formattedLineInformation = getFormattedLineInformation(
					originalLineInfo, lineText);

			if (!shouldReformat(document, formattedLineInformation)) {
				return;
			}

			// remove ending spaces.
			final int formattedLineStart = formattedLineInformation.getOffset();
			final int formattedTextEnd = formattedLineStart
					+ formattedLineInformation.getLength();
			final int originalTextEnd = orginalLineStart + originalLineLength;
			if (formattedTextEnd != originalTextEnd) {
				document.replace(formattedTextEnd, originalTextEnd
						- formattedTextEnd, ""); //$NON-NLS-1$
				// in case there is no text in the line just quit (since the
				// formatted of empty line is empty line)
				if (formattedLineStart == formattedTextEnd) {
					return;
				}
			}

			// get regions
			final int startingWhiteSpaces = formattedLineStart
					- orginalLineStart;
			final IIndentationStrategy insertionStrategy;
			final IStructuredDocumentRegion sdRegion = document
					.getRegionAtCharacterOffset(formattedLineStart);
			ITextRegion firstTokenInLine = sdRegion
					.getRegionAtCharacterOffset(formattedLineStart);
			ITextRegion lastTokenInLine = null;
			int regionStart = sdRegion.getStartOffset(firstTokenInLine);
			if (firstTokenInLine instanceof ITextRegionContainer) {
				ITextRegionContainer container = (ITextRegionContainer) firstTokenInLine;
				firstTokenInLine = container
						.getRegionAtCharacterOffset(formattedLineStart);
				regionStart += firstTokenInLine.getStart();
			}
			int scriptRegionLength = 0;
			if (firstTokenInLine instanceof IPhpScriptRegion) {
				IPhpScriptRegion scriptRegion = (IPhpScriptRegion) firstTokenInLine;
				if (scriptRegion.getEnd() - 1 < formattedLineStart) {
					return;
				}
				scriptRegionLength = scriptRegion.getStart();
				firstTokenInLine = scriptRegion.getPhpToken(formattedLineStart
						- regionStart);
				if (firstTokenInLine.getStart() + sdRegion.getStartOffset() < orginalLineStart
						&& firstTokenInLine.getType() == PHPRegionTypes.WHITESPACE) {
					firstTokenInLine = scriptRegion
							.getPhpToken(formattedLineStart - regionStart
									+ firstTokenInLine.getLength());
				}
				if (formattedTextEnd <= scriptRegion.getEnd()) {

					lastTokenInLine = scriptRegion.getPhpToken(formattedTextEnd
							- regionStart - 1);
					if (lastTokenInLine.getEnd() + sdRegion.getStartOffset() > orginalLineStart
							+ originalLineLength
							&& lastTokenInLine.getType() == PHPRegionTypes.WHITESPACE) {
						lastTokenInLine = scriptRegion
								.getPhpToken(formattedTextEnd - regionStart - 1
										- lastTokenInLine.getLength());
					}
				}
			}

			// if the next char is not from this line
			if (firstTokenInLine == null)
				return;

			String firstTokenType = firstTokenInLine.getType();

			boolean formatThisLine = !isInHeredoc;
			if (firstTokenType == PHPRegionTypes.PHP_HEREDOC_TAG
					|| (lastTokenInLine != null && lastTokenInLine.getType() == PHPRegionTypes.PHP_HEREDOC_TAG)) {
				isInHeredoc = !isInHeredoc;
			}

			if (firstTokenType == PHPRegionTypes.PHP_CONSTANT_ENCAPSED_STRING) {
				int startLine = document.getLineOfOffset(firstTokenInLine
						.getStart() + scriptRegionLength);
				if (startLine < lineNumber) {
					ignoreLines.add(lineNumber);
					return;
				}
			}

			if (!formatThisLine) {
				ignoreLines.add(lineNumber);
				return;
			}
			if (firstTokenType == PHPRegionTypes.PHP_CASE
					|| firstTokenType == PHPRegionTypes.PHP_DEFAULT) {
				insertionStrategy = caseDefaultIndentationStrategy;
			} else if (isPHPCommentRegion(firstTokenType)) {
				insertionStrategy = commentIndentationStrategy;
			} else if (firstTokenType == PHPRegionTypes.PHP_CLOSETAG) {
				insertionStrategy = phpCloseTagIndentationStrategy;
			} else {
				insertionStrategy = getIndentationStrategy(lineText
						.charAt(startingWhiteSpaces));
			}

			// Fill the buffer with blanks as if we added a "\n" to the end of
			// the prev element.
			// insertionStrategy.placeMatchingBlanks(editor,doc,insertionStrtegyKey,resultBuffer,startOffset-1);
			insertionStrategy.placeMatchingBlanks(document, resultBuffer,
					lineNumber, document.getLineOffset(lineNumber));

			// replace the starting spaces
			final String newIndentation = resultBuffer.toString();
			final String oldIndentation = lineText.substring(0,
					startingWhiteSpaces);
			char newChar = '\0';
			if (newIndentation.length() > 0) {
				newChar = newIndentation.charAt(0);
			}
			char oldChar = '\0';
			if (oldIndentation.length() > 0) {
				oldChar = oldIndentation.charAt(0);
			}
			if (newIndentation.length() != oldIndentation.length()
					|| newChar != oldChar) {
				document.replaceText(sdRegion, orginalLineStart,
						startingWhiteSpaces, newIndentation);
			}

		} catch (BadLocationException e) {
			Logger.logException(e);
		}
	}

	/**
	 * @return whether we are inside a php comment
	 */
	private boolean isPHPCommentRegion(String tokenType) {
		return (tokenType == PHPRegionTypes.PHP_COMMENT
				|| tokenType == PHPRegionTypes.PHP_COMMENT_END
				|| tokenType == PHPRegionTypes.PHPDOC_COMMENT || tokenType == PHPRegionTypes.PHPDOC_COMMENT_END);
	}

	/**
	 * @return the formatted line (without whitespaces) information Note: this
	 *         is an O(n) implementation (firstly it looks a bit complicated but
	 *         it worth it, the previous version was 3*n on the string's length)
	 */
	private IRegion getFormattedLineInformation(IRegion lineInfo,
			String lineText) {
		// start checking from left and right to the center
		int leftNonWhitespaceChar = 0;
		int rightNonWhitespaceChar = lineText.length() - 1;
		final char[] chars = lineText.toCharArray();
		boolean keepSearching = true;

		while (keepSearching) {
			final boolean leftIsWhiteSpace = chars[leftNonWhitespaceChar] == CHAR_SPACE
					|| chars[leftNonWhitespaceChar] == CHAR_TAB;
			final boolean rightIsWhiteSpace = chars[rightNonWhitespaceChar] == CHAR_SPACE
					|| chars[rightNonWhitespaceChar] == CHAR_TAB;
			if (leftIsWhiteSpace)
				leftNonWhitespaceChar++;
			if (rightIsWhiteSpace)
				rightNonWhitespaceChar--;
			keepSearching = (leftIsWhiteSpace || rightIsWhiteSpace)
					&& (leftNonWhitespaceChar < rightNonWhitespaceChar);
		}

		// if line is empty then the indexes were switched
		if (leftNonWhitespaceChar > rightNonWhitespaceChar)
			return new SimpleStructuredRegion(lineInfo.getOffset(), 0);

		// if there are no changes - return the original line information, else
		// build a fixed region
		return leftNonWhitespaceChar == 0
				&& rightNonWhitespaceChar == lineText.length() - 1 ? lineInfo
				: new SimpleStructuredRegion(lineInfo.getOffset()
						+ leftNonWhitespaceChar, rightNonWhitespaceChar
						- leftNonWhitespaceChar + 1);
	}

	private boolean shouldReformat(IStructuredDocument document,
			IRegion lineInfo) {
		final String checkedLineBeginState = FormatterUtils.getPartitionType(
				document, lineInfo.getOffset());
		return ((checkedLineBeginState == PHPPartitionTypes.PHP_DEFAULT)
				|| (checkedLineBeginState == PHPPartitionTypes.PHP_MULTI_LINE_COMMENT)
				|| (checkedLineBeginState == PHPPartitionTypes.PHP_SINGLE_LINE_COMMENT)
				|| (checkedLineBeginState == PHPPartitionTypes.PHP_DOC) || (checkedLineBeginState == PHPPartitionTypes.PHP_QUOTED_STRING));
	}

	protected final int getStart() {
		return start;
	}

	protected final int getLength() {
		return length;
	}

	public Set<Integer> getIgnoreLines() {
		return ignoreLines;
	}

	protected IIndentationStrategy getIndentationStrategy(char c) {
		if (c == '}') {
			return curlyCloseIndentationStrategy;
		}
		return getDefaultIndentationStrategy();
	}

	private IIndentationStrategy getDefaultIndentationStrategy() {
		return defaultIndentationStrategy;
	}
}
