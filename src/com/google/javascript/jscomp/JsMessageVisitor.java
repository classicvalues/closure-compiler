/*
 * Copyright 2008 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.CaseFormat;
import com.google.debugging.sourcemap.proto.Mapping.OriginalMapping;
import com.google.javascript.jscomp.JsMessage.PlaceholderFormatException;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.parsing.parser.util.format.SimpleFormat;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Traverses across parsed tree and finds I18N messages. Then it passes it to {@link
 * JsMessageVisitor#processJsMessage(JsMessage, JsMessageDefinition)}.
 */
@GwtIncompatible("JsMessage, java.util.regex")
public abstract class JsMessageVisitor extends AbstractPostOrderCallback implements CompilerPass {

  private static final String MSG_FUNCTION_NAME = "goog.getMsg";
  private static final String MSG_FALLBACK_FUNCTION_NAME = "goog.getMsgWithFallback";

  static final DiagnosticType MESSAGE_HAS_NO_DESCRIPTION =
      DiagnosticType.warning(
          "JSC_MSG_HAS_NO_DESCRIPTION", "Message {0} has no description. Add @desc JsDoc tag.");

  static final DiagnosticType MESSAGE_HAS_NO_TEXT =
      DiagnosticType.warning(
          "JSC_MSG_HAS_NO_TEXT",
          "Message value of {0} is just an empty string. " + "Empty messages are forbidden.");

  public static final DiagnosticType MESSAGE_TREE_MALFORMED =
      DiagnosticType.error("JSC_MSG_TREE_MALFORMED", "Message parse tree malformed. {0}");

  static final DiagnosticType MESSAGE_HAS_NO_VALUE =
      DiagnosticType.error("JSC_MSG_HAS_NO_VALUE", "message node {0} has no value");

  static final DiagnosticType MESSAGE_DUPLICATE_KEY =
      DiagnosticType.error(
          "JSC_MSG_KEY_DUPLICATED",
          "duplicate message variable name found for {0}, " + "initial definition {1}:{2}");

  static final DiagnosticType MESSAGE_NODE_IS_ORPHANED =
      DiagnosticType.warning(
          "JSC_MSG_ORPHANED_NODE",
          MSG_FUNCTION_NAME + "() function could be used only with MSG_* property or variable");

  public static final DiagnosticType MESSAGE_NOT_INITIALIZED_USING_NEW_SYNTAX =
      DiagnosticType.warning(
          "JSC_MSG_NOT_INITIALIZED_USING_NEW_SYNTAX",
          "message not initialized using " + MSG_FUNCTION_NAME);

  public static final DiagnosticType BAD_FALLBACK_SYNTAX =
      DiagnosticType.error(
          "JSC_MSG_BAD_FALLBACK_SYNTAX",
          SimpleFormat.format(
              "Bad syntax. " + "Expected syntax: %s(MSG_1, MSG_2)", MSG_FALLBACK_FUNCTION_NAME));

  public static final DiagnosticType FALLBACK_ARG_ERROR =
      DiagnosticType.error(
          "JSC_MSG_FALLBACK_ARG_ERROR", "Could not find message entry for fallback argument {0}");

  static final String MSG_PREFIX = "MSG_";

  /**
   * ScopedAliases pass transforms the goog.scope declarations to have a unique id as prefix.
   *
   * <p>After '$jscomp$scope$' expect some sequence of digits and dollar signs ending in '$MSG_
   */
  private static final Pattern SCOPED_ALIASES_PREFIX_PATTERN =
      Pattern.compile("\\$jscomp\\$scope\\$\\S+\\$MSG_");

  /**
   * Pattern for unnamed messages.
   *
   * <p>All JS messages in JS code should have unique name but messages in generated code (i.e. from
   * soy template) could have duplicated message names. Later we replace the message names with ids
   * constructed as a hash of the message content.
   *
   * <p><a href="https://github.com/google/closure-templates">Soy</a> generates messages with names
   * MSG_UNNAMED.* . This pattern recognizes such messages.
   */
  private static final Pattern MSG_UNNAMED_PATTERN = Pattern.compile("MSG_UNNAMED.*");

  private static final Pattern CAMELCASE_PATTERN = Pattern.compile("[a-z][a-zA-Z\\d]*[_\\d]*");

  static final String HIDDEN_DESC_PREFIX = "@hidden";

  // For old-style JS messages
  private static final String DESC_SUFFIX = "_HELP";

  private final JsMessage.Style style;
  private final JsMessage.IdGenerator idGenerator;
  final AbstractCompiler compiler;

  /**
   * The names encountered associated with their defining node and source. We use it for tracking
   * duplicated message ids in the source code.
   */
  private final Map<String, MessageLocation> messageNames = new HashMap<>();

  /** Track unnamed messages by Var, not string, as they are not guaranteed to be globally unique */
  private final Map<Var, JsMessage> unnamedMessages = new HashMap<>();

  /**
   * List of found goog.getMsg call nodes.
   *
   * <p>When we visit goog.getMsg() node we add it, and later when we visit its parent we remove it.
   * All nodes that are left at the end of traversing are orphaned nodes. It means have no
   * corresponding var or property node.
   */
  private final Set<Node> googMsgNodes = new HashSet<>();

  private final CheckLevel checkLevel;

  /**
   * Creates JS message visitor.
   *
   * @param compiler the compiler instance
   * @param style style that should be used during parsing
   * @param idGenerator generator that used for creating unique ID for the message
   */
  protected JsMessageVisitor(
      AbstractCompiler compiler, JsMessage.Style style, JsMessage.IdGenerator idGenerator) {

    this.compiler = compiler;
    this.style = style;
    this.idGenerator = idGenerator;

    checkLevel = (style == JsMessage.Style.CLOSURE) ? CheckLevel.ERROR : CheckLevel.WARNING;

    // TODO(anatol): add flag that decides whether to process UNNAMED messages.
    // Some projects would not want such functionality (unnamed) as they don't
    // use SOY templates.
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, this);

    for (Node msgNode : googMsgNodes) {
      compiler.report(JSError.make(msgNode, checkLevel, MESSAGE_NODE_IS_ORPHANED));
    }
  }

  @Override
  public void visit(NodeTraversal traversal, Node node, Node unused) {
    collectGetMsgCall(traversal, node);
    checkMessageInitialization(traversal, node);
  }

  /** This method is called for every Node in the sources AST. */
  private void checkMessageInitialization(NodeTraversal traversal, Node node) {
    final Node parent = node.getParent();

    final String originalMessageKey;
    String possiblyObfuscatedMessageKey;
    final Node msgNode;
    final boolean isVar;

    switch (node.getToken()) {
      case NAME:
        // Case: `var MSG_HELLO = 'Message';`
        if (parent == null || !NodeUtil.isNameDeclaration(parent)) {
          return;
        }

        possiblyObfuscatedMessageKey = node.getString();
        originalMessageKey = node.getOriginalName();
        msgNode = node.getFirstChild();
        isVar = true;
        break;

      case ASSIGN:
        // Case: `somenamespace.someclass.MSG_HELLO = 'Message';`
        Node getProp = node.getFirstChild();
        if (!getProp.isGetProp()) {
          return;
        }

        possiblyObfuscatedMessageKey = getProp.getString();
        originalMessageKey = getProp.getOriginalName();
        msgNode = node.getLastChild();
        isVar = false;
        break;

      case STRING_KEY:
        // Case: `var t = {MSG_HELLO: 'Message'}`;
        if (node.isQuotedString() || !node.hasChildren() || parent.isObjectPattern()) {
          // Don't require goog.getMsg() for quoted keys
          // Case: `var msgs = { 'MSG_QUOTED': anything };`
          //
          // Don't try to require goog.getMsg() for destructuring assignment targets.
          // goog.getMsg() needs to be used in a direct assignment to a variable or property
          // only.
          // Case: `var {MSG_HELLO} = anything;
          // Case: `var {something: MSG_HELLO} = anything;
          return;
        }
        checkState(parent.isObjectLit(), parent);

        possiblyObfuscatedMessageKey = node.getString();
        originalMessageKey = node.getOriginalName();
        msgNode = node.getFirstChild();
        isVar = false;
        break;

      default:
        return;
    }

    String messageKey =
        originalMessageKey != null ? originalMessageKey : possiblyObfuscatedMessageKey;

    // If we've reached this point, then messageKey is the name of a variable or a property that is
    // being assigned a value and msgNode is the Node representing the value being assigned.
    // However, we haven't actually determined yet that name looks like it should be a translatable
    // message or that the value is a call to goog.getMsg().

    // Is this a message name?
    boolean isNewStyleMessage = msgNode != null && msgNode.isCall();

    if (!isMessageName(messageKey, isNewStyleMessage)) {
      return;
    }

    if (msgNode == null) {
      compiler.report(JSError.make(node, MESSAGE_HAS_NO_VALUE, messageKey));
      return;
    }

    if (isLegalMessageVarAlias(msgNode)) {
      return;
    }

    // Report a warning if a qualified messageKey that looks like a message (e.g. "a.b.MSG_X")
    // doesn't use goog.getMsg().
    if (isNewStyleMessage) {
      googMsgNodes.remove(msgNode);
    } else if (style != JsMessage.Style.LEGACY) {
      // TODO(johnlenz): promote this to an error once existing conflicts have been
      // cleaned up.
      compiler.report(JSError.make(node, MESSAGE_NOT_INITIALIZED_USING_NEW_SYNTAX));
      if (style == JsMessage.Style.CLOSURE) {
        // Don't extract the message if we aren't accepting LEGACY messages
        return;
      }
    }

    boolean isUnnamedMsg = isUnnamedMessageName(messageKey);

    JsMessage.Builder builder = new JsMessage.Builder(isUnnamedMsg ? null : messageKey);
    OriginalMapping mapping =
        compiler.getSourceMapping(traversal.getSourceName(), node.getLineno(), node.getCharno());
    if (mapping != null) {
      builder.setSourceName(mapping.getOriginalFile() + ":" + mapping.getLineNumber());
    } else {
      builder.setSourceName(traversal.getSourceName() + ":" + node.getLineno());
    }

    try {
      if (isVar) {
        extractMessageFromVariable(builder, node, parent, parent.getParent());
      } else {
        extractMessageFrom(builder, msgNode, node);
      }
    } catch (MalformedException ex) {
      compiler.report(JSError.make(ex.getNode(), MESSAGE_TREE_MALFORMED, ex.getMessage()));
      return;
    }

    JsMessage extractedMessage = builder.build(idGenerator);

    // If asked to check named internal messages.
    if (!isUnnamedMsg && !extractedMessage.isExternal()) {
      checkIfMessageDuplicated(messageKey, msgNode);
    }
    if (isUnnamedMsg) {
      trackUnnamedMessage(traversal, extractedMessage, possiblyObfuscatedMessageKey);
    } else {
      trackNormalMessage(extractedMessage, messageKey, msgNode);
    }

    if (extractedMessage.isEmpty()) {
      // value of the message is an empty string. Translators do not like it.
      compiler.report(JSError.make(node, MESSAGE_HAS_NO_TEXT, messageKey));
    }

    // New-style messages must have descriptions. We don't emit a warning
    // for legacy-style messages, because there are thousands of
    // them in legacy code that are not worth the effort to fix, since they've
    // already been translated anyway.
    String desc = extractedMessage.getDesc();
    if (isNewStyleMessage
        && (desc == null || desc.trim().isEmpty())
        && !extractedMessage.isExternal()) {
      compiler.report(JSError.make(node, MESSAGE_HAS_NO_DESCRIPTION, messageKey));
    }

    JsMessageDefinition msgDefinition = new JsMessageDefinition(msgNode);
    processJsMessage(extractedMessage, msgDefinition);
  }

  private void collectGetMsgCall(NodeTraversal traversal, Node call) {
    if (!call.isCall()) {
      return;
    }

    // goog.getMsg()
    if (call.getFirstChild().matchesQualifiedName(MSG_FUNCTION_NAME)) {
      googMsgNodes.add(call);
    } else if (call.getFirstChild().matchesQualifiedName(MSG_FALLBACK_FUNCTION_NAME)) {
      visitFallbackFunctionCall(traversal, call);
    }
  }

  /**
   * Track a message for later retrieval.
   *
   * <p>This is used for tracking duplicates, and for figuring out message fallback. Not all message
   * types are trackable, because that would require a more sophisticated analysis. e.g., function
   * f(s) { s.MSG_UNNAMED_X = 'Some untrackable message'; }
   */
  private void trackNormalMessage(JsMessage message, String msgName, Node msgNode) {
    MessageLocation location = new MessageLocation(message, msgNode);
    messageNames.put(msgName, location);
  }

  /**
   * Track an unnamed message for later retrieval.
   *
   * <p>This is used for figuring out message fallbacks. Message duplicates are allowed for unnamed
   * messages.
   */
  private void trackUnnamedMessage(NodeTraversal t, JsMessage message, String msgNameInScope) {
    Var var = t.getScope().getVar(msgNameInScope);
    if (var != null) {
      unnamedMessages.put(var, message);
    }
  }

  /**
   * Defines any special cases that are exceptions to what would otherwise be illegal message
   * assignments.
   *
   * <p>These exceptions are generally due to the pass being designed before new syntax was
   * introduced.
   *
   * @param msgNode Node representing the value assigned to the message variable or property
   */
  private static boolean isLegalMessageVarAlias(Node msgNode) {
    if (msgNode.isGetProp()
        && msgNode.isQualifiedName()
        && msgNode.getString().startsWith(MSG_PREFIX)) {
      // Case: `foo.Thing.MSG_EXAMPLE_ALIAS = bar.OtherThing.MSG_EXAMPLE;`
      //
      // This kind of construct is created by TypeScript code generation and
      // ConcretizeStaticInheritanceForInlining. Just ignore it; the message will have already been
      // extracted
      // from the base class.
      return true;
    }

    if (!msgNode.isName()) {
      return false;
    }

    String originalName =
        (msgNode.getOriginalName() != null) ? msgNode.getOriginalName() : msgNode.getString();

    if (originalName.startsWith(MSG_PREFIX)) {
      // Creating an alias for a message is also allowed, and sometimes happens in generated code,
      // including some of the code generated by this compiler's transpilations.
      // e.g.
      // `var MSG_EXAMPLE_ALIAS = MSG_EXAMPLE;`
      // `var {MSG_HELLO_ALIAS} = MSG_HELLO;
      // `var {MSG_HELLO_ALIAS: goog$module$my$module_MSG_HELLO} = x;`).
      // `exports = {MSG_FOO}`
      // or `exports = {MSG_FOO: MSG_FOO}` when used with declareLegacyNamespace.
      return true;
    }

    return false;
  }

  /** Get a previously tracked unnamed message */
  private JsMessage getTrackedUnnamedMessage(NodeTraversal t, String msgNameInScope) {
    Var var = t.getScope().getVar(msgNameInScope);
    if (var != null) {
      return unnamedMessages.get(var);
    }
    return null;
  }

  /** Get a previously tracked message. */
  private JsMessage getTrackedNormalMessage(String msgName) {
    MessageLocation location = messageNames.get(msgName);
    return location == null ? null : location.message;
  }

  /**
   * Checks if message already processed. If so - it generates 'message duplicated' compiler error.
   *
   * @param msgName the name of the message
   * @param msgNode the node that represents JS message
   */
  private void checkIfMessageDuplicated(String msgName, Node msgNode) {
    if (messageNames.containsKey(msgName)) {
      MessageLocation location = messageNames.get(msgName);
      compiler.report(
          JSError.make(
              msgNode,
              MESSAGE_DUPLICATE_KEY,
              msgName,
              location.messageNode.getSourceFileName(),
              Integer.toString(location.messageNode.getLineno())));
    }
  }

  /**
   * Creates a {@link JsMessage} for a JS message defined using a JS variable declaration (e.g
   * <code>var MSG_X = ...;</code>).
   *
   * @param builder the message builder
   * @param nameNode a NAME node for a JS message variable
   * @param parentNode a VAR node, parent of {@code nameNode}
   * @param grandParentNode the grandparent of {@code nameNode}. This node is only used to get meta
   *     data about the message that might be surrounding it (e.g. a message description). This
   *     argument may be null if the meta data is not needed.
   * @throws MalformedException if {@code varNode} does not correspond to a valid JS message VAR
   *     node
   */
  private void extractMessageFromVariable(
      JsMessage.Builder builder, Node nameNode, Node parentNode, @Nullable Node grandParentNode)
      throws MalformedException {

    // Determine the message's value
    Node valueNode = nameNode.getFirstChild();
    switch (valueNode.getToken()) {
      case STRINGLIT:
      case ADD:
        maybeInitMetaDataFromJsDocOrHelpVar(builder, parentNode, grandParentNode);
        builder.appendStringPart(extractStringFromStringExprNode(valueNode));
        break;
      case FUNCTION:
        maybeInitMetaDataFromJsDocOrHelpVar(builder, parentNode, grandParentNode);
        extractFromFunctionNode(builder, valueNode);
        break;
      case CALL:
        maybeInitMetaDataFromJsDoc(builder, parentNode);
        extractFromCallNode(builder, valueNode);
        break;
      default:
        throw new MalformedException(
            "Cannot parse value of message " + builder.getKey(), valueNode);
    }
  }

  /**
   * Creates a {@link JsMessage} for a JS message defined using an assignment to a qualified name
   * (e.g <code>a.b.MSG_X = goog.getMsg(...);</code>).
   *
   * @param builder the message builder
   * @param valueNode a node in a JS message value
   * @param docNode the node containing the jsdoc.
   * @throws MalformedException if {@code getPropNode} does not correspond to a valid JS message
   *     node
   */
  private void extractMessageFrom(JsMessage.Builder builder, Node valueNode, Node docNode)
      throws MalformedException {
    maybeInitMetaDataFromJsDoc(builder, docNode);
    extractFromCallNode(builder, valueNode);
  }

  /**
   * Initializes the meta data in a JsMessage by examining the nodes just before and after a message
   * VAR node.
   *
   * @param builder the message builder whose meta data will be initialized
   * @param varNode the message VAR node
   * @param parentOfVarNode {@code varNode}'s parent node
   */
  private void maybeInitMetaDataFromJsDocOrHelpVar(
      JsMessage.Builder builder, Node varNode, @Nullable Node parentOfVarNode)
      throws MalformedException {

    // First check description in @desc
    if (maybeInitMetaDataFromJsDoc(builder, varNode)) {
      return;
    }

    // Check the preceding node for meta data
    if ((parentOfVarNode != null) && maybeInitMetaDataFromHelpVar(builder, varNode.getPrevious())) {
      return;
    }

    // Check the subsequent node for meta data
    maybeInitMetaDataFromHelpVar(builder, varNode.getNext());
  }

  /**
   * Initializes the meta data in a JsMessage by examining a node just before or after a message VAR
   * node.
   *
   * @param builder the message builder whose meta data will be initialized
   * @param sibling a node adjacent to the message VAR node
   * @return true iff message has corresponding description variable
   */
  private static boolean maybeInitMetaDataFromHelpVar(
      JsMessage.Builder builder, @Nullable Node sibling) throws MalformedException {
    if ((sibling != null) && (sibling.isVar())) {
      Node nameNode = sibling.getFirstChild();
      String name = nameNode.getString();
      if (name.equals(builder.getKey() + DESC_SUFFIX)) {
        Node valueNode = nameNode.getFirstChild();
        String desc = extractStringFromStringExprNode(valueNode);
        if (desc.startsWith(HIDDEN_DESC_PREFIX)) {
          builder.setDesc(desc.substring(HIDDEN_DESC_PREFIX.length()).trim());
          builder.setIsHidden(true);
        } else {
          builder.setDesc(desc);
        }
        return true;
      }
    }
    return false;
  }

  /**
   * Initializes the meta data in a message builder given a node that may contain JsDoc properties.
   *
   * @param builder the message builder whose meta data will be initialized
   * @param node the node with the message's JSDoc properties
   * @return true if message has JsDoc with valid description in @desc annotation
   */
  private static boolean maybeInitMetaDataFromJsDoc(JsMessage.Builder builder, Node node) {
    boolean messageHasDesc = false;
    JSDocInfo info = node.getJSDocInfo();
    if (info != null) {
      String desc = info.getDescription();
      if (desc != null) {
        builder.setDesc(desc);
        messageHasDesc = true;
      }
      if (info.isHidden()) {
        builder.setIsHidden(true);
      }
      if (info.getMeaning() != null) {
        builder.setMeaning(info.getMeaning());
      }
      if (info.getAlternateMessageId() != null) {
        builder.setAlternateId(info.getAlternateMessageId());
      }
    }

    return messageHasDesc;
  }

  /**
   * Returns the string value associated with a node representing a JS string or several JS strings
   * added together (e.g. {@code 'str'} or {@code 's' + 't' + 'r'}).
   *
   * @param node the node from where we extract the string
   * @return String representation of the node
   * @throws MalformedException if the parsed message is invalid
   */
  private static String extractStringFromStringExprNode(Node node) throws MalformedException {
    switch (node.getToken()) {
      case STRINGLIT:
        return node.getString();
      case TEMPLATELIT:
        if (node.hasOneChild()) {
          // Cooked string can be null only for tagged template literals.
          // A tagged template literal would hit the default case below.
          return checkNotNull(node.getFirstChild().getCookedString());
        } else {
          throw new MalformedException(
              "Template literals with substitutions are not allowed.", node);
        }
      case ADD:
        StringBuilder sb = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
          sb.append(extractStringFromStringExprNode(child));
        }
        return sb.toString();
      default:
        throw new MalformedException(
            "STRING or ADD node expected; found: " + node.getToken(), node);
    }
  }

  /**
   * Initializes a message builder from a FUNCTION node.
   *
   * <p>
   *
   * <pre>
   * The tree should look something like:
   *
   * function
   *  |-- name
   *  |-- lp
   *  |   |-- name <arg1>
   *  |    -- name <arg2>
   *   -- block
   *      |
   *       --return
   *           |
   *            --add
   *               |-- string foo
   *                -- name <arg1>
   * </pre>
   *
   * @param builder the message builder
   * @param node the function node that contains a message
   * @throws MalformedException if the parsed message is invalid
   */
  private void extractFromFunctionNode(JsMessage.Builder builder, Node node)
      throws MalformedException {
    Set<String> phNames = new HashSet<>();

    for (Node fnChild = node.getFirstChild(); fnChild != null; fnChild = fnChild.getNext()) {
      switch (fnChild.getToken()) {
        case NAME:
          // This is okay. The function has a name, but it is empty.
          break;
        case PARAM_LIST:
          // Parse the placeholder names from the function argument list.
          for (Node argumentNode = fnChild.getFirstChild();
              argumentNode != null;
              argumentNode = argumentNode.getNext()) {
            if (argumentNode.isName()) {
              String phName = argumentNode.getString();
              if (phNames.contains(phName)) {
                throw new MalformedException("Duplicate placeholder name: " + phName, argumentNode);
              } else {
                phNames.add(phName);
              }
            }
          }
          break;
        case BLOCK:
          // Build the message's value by examining the return statement
          Node returnNode = fnChild.getFirstChild();
          if (!returnNode.isReturn()) {
            throw new MalformedException(
                "RETURN node expected; found: " + returnNode.getToken(), returnNode);
          }
          for (Node child = returnNode.getFirstChild(); child != null; child = child.getNext()) {
            extractFromReturnDescendant(builder, child);
          }

          // Check that all placeholders from the message text have appropriate
          // object literal keys
          for (String phName : builder.getPlaceholders()) {
            if (!phNames.contains(phName)) {
              throw new MalformedException(
                  "Unrecognized message placeholder referenced: " + phName, returnNode);
            }
          }
          break;
        default:
          throw new MalformedException(
              "NAME, PARAM_LIST, or BLOCK node expected; found: " + node, fnChild);
      }
    }
  }

  /**
   * Appends value parts to the message builder by traversing the descendants of the given RETURN
   * node.
   *
   * @param builder the message builder
   * @param node the node from where we extract a message
   * @throws MalformedException if the parsed message is invalid
   */
  private static void extractFromReturnDescendant(JsMessage.Builder builder, Node node)
      throws MalformedException {

    switch (node.getToken()) {
      case STRINGLIT:
        builder.appendStringPart(node.getString());
        break;
      case NAME:
        builder.appendPlaceholderReference(node.getString());
        break;
      case ADD:
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
          extractFromReturnDescendant(builder, child);
        }
        break;
      default:
        throw new MalformedException(
            "STRING, NAME, or ADD node expected; found: " + node.getToken(), node);
    }
  }

  /**
   * Initializes a message builder from a CALL node.
   *
   * <p>The tree should look something like:
   *
   * <pre>
   * call
   *  |-- getprop
   *  |   |-- name 'goog'
   *  |   +-- string 'getMsg'
   *  |
   *  |-- string 'Hi {$userName}! Welcome to {$product}.'
   *  +-- objlit
   *  |   |-- string_key 'userName'
   *  |   |   +-- name 'someUserName'
   *  |   +-- string_key 'product'
   *  |       +-- call
   *  |           +-- name 'getProductName'
   *  +-- objlit // optional options bag
   *      |-- string_key 'example'
   *      |   + objlit
   *      |     |-- string_key 'userName'
   *      |     |   +-- 'Jonathan Tuttle'
   *      |     |-- string_key 'product'
   *      |     |   +-- 'Google Anonymizer'
   *      +-- string_key 'original_code'
   *            |-- string_key 'userName'
   *            |   +-- 'user.getName()'
   *            +-- string_key 'product'
   *                +-- string_key 'product.getName()'
   * </pre>
   *
   * @param builder the message builder
   * @param node the call node from where we extract the message
   * @throws MalformedException if the parsed message is invalid
   */
  private void extractFromCallNode(JsMessage.Builder builder, Node node) throws MalformedException {
    // Check the function being called
    if (!node.isCall()) {
      throw new MalformedException(
          "Message must be initialized using " + MSG_FUNCTION_NAME + " function.", node);
    }

    Node fnNameNode = node.getFirstChild();
    if (!fnNameNode.matchesQualifiedName(MSG_FUNCTION_NAME)) {
      throw new MalformedException(
          "Message initialized using unrecognized function. "
              + "Please use "
              + MSG_FUNCTION_NAME
              + "() instead.",
          fnNameNode);
    }

    // Get the message string
    Node stringLiteralNode = fnNameNode.getNext();
    if (stringLiteralNode == null) {
      throw new MalformedException("Message string literal expected", stringLiteralNode);
    }
    // Parse the message string and append parts to the builder
    parseMessageTextNode(builder, stringLiteralNode);

    Node valuesObjLit = stringLiteralNode.getNext();
    Set<String> phNames = new HashSet<>();
    if (valuesObjLit != null) {
      // Register the placeholder names
      if (!valuesObjLit.isObjectLit()) {
        throw new MalformedException("OBJLIT node expected", valuesObjLit);
      }
      for (Node aNode = valuesObjLit.getFirstChild(); aNode != null; aNode = aNode.getNext()) {
        if (!aNode.isStringKey()) {
          throw new MalformedException("STRING_KEY node expected as OBJLIT key", aNode);
        }
        String phName = aNode.getString();
        if (!isLowerCamelCaseWithNumericSuffixes(phName)) {
          throw new MalformedException("Placeholder name not in lowerCamelCase: " + phName, aNode);
        }

        if (phNames.contains(phName)) {
          throw new MalformedException("Duplicate placeholder name: " + phName, aNode);
        }

        phNames.add(phName);
      }
    }

    // Now we have all the placeholder names that were referenced in the message string.
    // Verify that other references to placeholder names match one of these.
    Set<String> usedPlaceholders = builder.getPlaceholders();

    final Node optionsBagArgument = valuesObjLit == null ? null : valuesObjLit.getNext();

    if (optionsBagArgument != null) {
      extractPlaceholderInfoFromOptionsBagArgument(optionsBagArgument, usedPlaceholders, builder);
    }

    // Check that all placeholders from the message text have appropriate objlit
    // values
    for (String phName : usedPlaceholders) {
      if (!phNames.contains(phName)) {
        throw new MalformedException(
            "Unrecognized message placeholder referenced: " + phName, node);
      }
    }

    // Check that objLiteral have only names that are present in the
    // message text
    for (String phName : phNames) {
      if (!usedPlaceholders.contains(phName)) {
        throw new MalformedException("Unused message placeholder: " + phName, node);
      }
    }
  }

  private void extractPlaceholderInfoFromOptionsBagArgument(
      Node optionsBagArgument, Set<String> knownPlaceholders, JsMessage.Builder builder)
      throws MalformedException {
    if (!optionsBagArgument.isObjectLit()) {
      throw new MalformedException("object literal expected", optionsBagArgument);
    }

    for (Node stringKey = optionsBagArgument.getFirstChild();
        stringKey != null;
        stringKey = stringKey.getNext()) {
      if (!stringKey.isStringKey()) {
        throw new MalformedException("string key expected", stringKey);
      }
      if (stringKey.getString().equals("original_code")) {
        extractOriginalCodeMapFromObjectLiteral(
            stringKey.getOnlyChild(), knownPlaceholders, builder);
      } else if (stringKey.getString().equals("example")) {
        extractExampleMapFromObjectLiteral(stringKey.getOnlyChild(), knownPlaceholders, builder);
      } // TODO(bradfordcsmith): Consider reporting an error for an unexpected option
    }
  }

  private void extractOriginalCodeMapFromObjectLiteral(
      Node objectLiteral, Set<String> knownPlaceholderNames, JsMessage.Builder builder)
      throws MalformedException {
    if (!objectLiteral.isObjectLit()) {
      throw new MalformedException("object literal expected", objectLiteral);
    }
    final Map<String, String> stringToStringMap =
        extractPlaceholderNameToStringMapFromObjectLiteral(objectLiteral, knownPlaceholderNames);
    builder.setPlaceholderNameToOriginalCodeMap(stringToStringMap);
  }

  private void extractExampleMapFromObjectLiteral(
      Node objectLiteral, Set<String> knownPlaceholderNames, JsMessage.Builder builder)
      throws MalformedException {
    if (!objectLiteral.isObjectLit()) {
      throw new MalformedException("object literal expected", objectLiteral);
    }
    final Map<String, String> stringToStringMap =
        extractPlaceholderNameToStringMapFromObjectLiteral(objectLiteral, knownPlaceholderNames);
    builder.setPlaceholderNameToExampleMap(stringToStringMap);
  }

  private Map<String, String> extractPlaceholderNameToStringMapFromObjectLiteral(
      Node objectLiteral, Set<String> knownPlaceholderNames) throws MalformedException {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    for (Node stringKey = objectLiteral.getFirstChild();
        stringKey != null;
        stringKey = stringKey.getNext()) {
      if (!stringKey.isStringKey()) {
        throw new MalformedException("string key expected", stringKey);
      }
      Node valueNode = stringKey.getOnlyChild();
      if (!valueNode.isStringLit()) {
        throw new MalformedException("string literal expected", valueNode);
      }
      String placeholderName = stringKey.getString();
      String value = valueNode.getString();
      if (!knownPlaceholderNames.contains(placeholderName)) {
        throw new MalformedException("unexpected placeholder name", stringKey);
      }
      if (map.containsKey(placeholderName)) {
        throw new MalformedException("duplicate string key", stringKey);
      }
      map.put(placeholderName, value);
    }
    return map;
  }

  /**
   * Appends the message parts in a JS message value extracted from the given text node.
   *
   * @param builder the JS message builder to append parts to
   * @param node the node with string literal that contains the message text
   * @throws MalformedException if {@code value} contains a reference to an unregistered placeholder
   */
  private static void parseMessageTextNode(JsMessage.Builder builder, Node node)
      throws MalformedException {
    String value = extractStringFromStringExprNode(node);

    try {
      builder.setMsgText(value);
    } catch (PlaceholderFormatException e) {
      throw new MalformedException(
          "Placeholder incorrectly formatted in: " + builder.getKey(), node);
    }
  }

  /** Visit a call to goog.getMsgWithFallback. */
  private void visitFallbackFunctionCall(NodeTraversal t, Node call) {
    // Check to make sure the function call looks like:
    // goog.getMsgWithFallback(MSG_1, MSG_2);
    // or:
    // goog.getMsgWithFallback(some.import.MSG_1, some.import.MSG_2);
    if (!call.hasXChildren(3)
        || !isMessageIdentifier(call.getSecondChild())
        || !isMessageIdentifier(call.getLastChild())) {
      compiler.report(JSError.make(call, BAD_FALLBACK_SYNTAX));
      return;
    }

    Node firstArg = call.getSecondChild();
    JsMessage firstMessage = getJsMessageFromNode(t, firstArg);
    if (firstMessage == null) {
      compiler.report(JSError.make(firstArg, FALLBACK_ARG_ERROR, firstArg.getQualifiedName()));
      return;
    }

    Node secondArg = firstArg.getNext();
    JsMessage secondMessage = getJsMessageFromNode(t, secondArg);
    if (secondMessage == null) {
      compiler.report(JSError.make(secondArg, FALLBACK_ARG_ERROR, secondArg.getQualifiedName()));
      return;
    }

    processMessageFallback(call, firstMessage, secondMessage);
  }

  /**
   * Processes found JS message. Several examples of "standard" processing routines are:
   *
   * <ol>
   *   <li>extract all JS messages
   *   <li>replace JS messages with localized versions for some specific language
   *   <li>check that messages have correct syntax and present in localization bundle
   * </ol>
   *
   * @param message the found message
   * @param definition the definition of the object and usually contains all additional message
   *     information like message node/parent's node
   */
  protected abstract void processJsMessage(JsMessage message, JsMessageDefinition definition);

  /**
   * Processes the goog.getMsgWithFallback primitive. goog.getMsgWithFallback(MSG_1, MSG_2);
   *
   * <p>By default, does nothing.
   */
  void processMessageFallback(Node callNode, JsMessage message1, JsMessage message2) {}

  /** Returns whether the given JS identifier is a valid JS message name. */
  boolean isMessageName(String identifier, boolean isNewStyleMessage) {
    return (identifier.startsWith(MSG_PREFIX) || isScopedAliasesPrefix(identifier))
        && (style == JsMessage.Style.CLOSURE
            || isNewStyleMessage
            || !identifier.endsWith(DESC_SUFFIX));
  }

  private static boolean isMessageIdentifier(Node node) {
    String qname = node.getQualifiedName();
    return qname != null && qname.contains(MSG_PREFIX);
  }

  /**
   * Extracts a message name (e.g. MSG_FOO) from either a NAME node or a GETPROP node. This should
   * cover all of the following cases:
   *
   * <ol>
   *   <li>a NAME node (e.g. MSG_FOO)
   *   <li>a NAME node which is the product of renaming (e.g. $module$contents$MSG_FOO)
   *   <li>a GETPROP node (e.g. some.import.MSG_FOO)
   * </ol>
   */
  private JsMessage getJsMessageFromNode(NodeTraversal t, Node node) {
    String messageName = node.getQualifiedName();
    if (messageName == null || !messageName.contains(MSG_PREFIX)) {
      return null;
    }

    String messageKey = messageName.substring(messageName.indexOf(MSG_PREFIX));
    if (isUnnamedMessageName(messageKey)) {
      return getTrackedUnnamedMessage(t, messageName);
    } else {
      return getTrackedNormalMessage(messageKey);
    }
  }

  /** Returns whether the given message name is in the unnamed namespace. */
  private static boolean isUnnamedMessageName(String identifier) {
    return MSG_UNNAMED_PATTERN.matcher(identifier).matches();
  }

  /**
   * Returns whether a string is nonempty, begins with a lowercase letter, and contains only digits
   * and underscores after the first underscore.
   */
  static boolean isLowerCamelCaseWithNumericSuffixes(String input) {
    return CAMELCASE_PATTERN.matcher(input).matches();
  }

  /**
   * Converts the given string from upper-underscore case to lower-camel case, preserving numeric
   * suffixes. For example: "NAME" -> "name" "A4_LETTER" -> "a4Letter" "START_SPAN_1_23" ->
   * "startSpan_1_23".
   */
  static String toLowerCamelCaseWithNumericSuffixes(String input) {
    // Determine where the numeric suffixes begin
    int suffixStart = input.length();
    while (suffixStart > 0) {
      char ch = '\0';
      int numberStart = suffixStart;
      while (numberStart > 0) {
        ch = input.charAt(numberStart - 1);
        if (Character.isDigit(ch)) {
          numberStart--;
        } else {
          break;
        }
      }
      if ((numberStart > 0) && (numberStart < suffixStart) && (ch == '_')) {
        suffixStart = numberStart - 1;
      } else {
        break;
      }
    }

    if (suffixStart == input.length()) {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, input);
    } else {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, input.substring(0, suffixStart))
          + input.substring(suffixStart);
    }
  }

  /**
   * Checks a node's type.
   *
   * @throws MalformedException if the node is null or the wrong type
   */
  protected void checkNode(@Nullable Node node, Token type) throws MalformedException {
    if (node == null) {
      throw new MalformedException("Expected node type " + type + "; found: null", node);
    }
    if (node.getToken() != type) {
      throw new MalformedException(
          "Expected node type " + type + "; found: " + node.getToken(), node);
    }
  }

  static class MalformedException extends Exception {
    private static final long serialVersionUID = 1L;

    private final Node node;

    MalformedException(String message, Node node) {
      super(message);
      this.node = node;
    }

    Node getNode() {
      return node;
    }
  }

  private static class MessageLocation {
    private final JsMessage message;
    private final Node messageNode;

    private MessageLocation(JsMessage message, Node messageNode) {
      this.message = message;
      this.messageNode = messageNode;
    }
  }

  public static boolean isScopedAliasesPrefix(String name) {
    return SCOPED_ALIASES_PREFIX_PATTERN.matcher(name).lookingAt();
  }

  public static String removeScopedAliasesPrefix(String name) {
    return SCOPED_ALIASES_PREFIX_PATTERN.matcher(name).replaceFirst("MSG_");
  }
}
