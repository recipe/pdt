package org.eclipse.php.internal.core.compiler.ast.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.dltk.ast.Modifiers;
import org.eclipse.dltk.ast.declarations.Argument;
import org.eclipse.dltk.ast.declarations.Declaration;
import org.eclipse.dltk.ast.declarations.MethodDeclaration;
import org.eclipse.dltk.ast.declarations.TypeDeclaration;
import org.eclipse.dltk.ast.expressions.CallArgumentsList;
import org.eclipse.dltk.ast.expressions.CallExpression;
import org.eclipse.dltk.ast.expressions.Expression;
import org.eclipse.dltk.ast.references.ConstantReference;
import org.eclipse.dltk.ast.references.SimpleReference;
import org.eclipse.dltk.ast.references.TypeReference;
import org.eclipse.dltk.ast.references.VariableReference;
import org.eclipse.dltk.ast.statements.Statement;
import org.eclipse.dltk.compiler.ISourceElementRequestor;
import org.eclipse.dltk.compiler.SourceElementRequestVisitor;
import org.eclipse.php.core.PHPSourceElementRequestorExtension;
import org.eclipse.php.internal.core.Logger;
import org.eclipse.php.internal.core.PHPCorePlugin;
import org.eclipse.php.internal.core.compiler.ast.nodes.*;

public class PHPSourceElementRequestor extends SourceElementRequestVisitor {

	private static final String CONSTRUCTOR_NAME = "__construct";
	/*
	 * This should replace the need for fInClass, fInMethod and fCurrentMethod
	 * since in php the type declarations can be nested.
	 */
	protected Stack<Declaration> declarations = new Stack<Declaration>();
	private PHPSourceElementRequestorExtension[] extensions;

	public PHPSourceElementRequestor(ISourceElementRequestor requestor, char[] contents, char[] filename) {
		super(requestor);

		// Load PHP source element requester extensions
		IConfigurationElement[] elements = Platform.getExtensionRegistry().getConfigurationElementsFor(PHPCorePlugin.ID, "phpSourceElementRequestors");
		List<PHPSourceElementRequestorExtension> requestors = new ArrayList<PHPSourceElementRequestorExtension>(elements.length);
		for (IConfigurationElement element : elements) {
			try {
				PHPSourceElementRequestorExtension extension = (PHPSourceElementRequestorExtension) element.createExecutableExtension("class");
				extension.setRequestor(fRequestor);
				extension.setContents(contents);
				extension.setFilename(new String(filename));

				requestors.add(extension);
			} catch (CoreException e) {
				Logger.logException(e);
			}
		}
		extensions = requestors.toArray(new PHPSourceElementRequestorExtension[requestors.size()]);
	}

	public MethodDeclaration getCurrentMethod() {
		Declaration currDecleration = declarations.peek();
		if (currDecleration instanceof MethodDeclaration) {
			return (MethodDeclaration) currDecleration;
		}
		return null;
	}
	
	/**
	 * Strips single or double quotes from the start and from the end of the given string
	 * @param name String
	 * @return
	 */
	private static String stripQuotes(String name) {
		int len = name.length();
		if (len > 1 && (name.charAt(0) == '\'' && name.charAt(len - 1) == '\'' || name.charAt(0) == '"' && name.charAt(len - 1) == '"')) {
			name = name.substring(1, len - 1);
		}
		return name;
	}

	public boolean endvisit(MethodDeclaration method) throws Exception {
		declarations.pop();

		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.endvisit(method);
		}
		return super.endvisit(method);
	}

	public boolean endvisit(TypeDeclaration type) throws Exception {
		declarations.pop();
		
		// resolve more type member declarations
		resolveMagicMembers(type); 
		
		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.endvisit(type);
		}
		
		return super.endvisit(type);
	}
	
	@SuppressWarnings("unchecked")
	public boolean visit(MethodDeclaration method) throws Exception {
		Declaration parentDeclaration = null;
		if (!declarations.empty()) {
			parentDeclaration = declarations.peek();
		}
				
		if (parentDeclaration instanceof InterfaceDeclaration) {
			method.setModifier(Modifiers.AccAbstract);
		}

		declarations.push(method);

		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.visit(method);
		}
		
		// The rest is copied from the super implementation just for setting isConstructor flag:
		this.fNodes.push(method);
		List args = method.getArguments();

		String[] parameter = new String[args.size()];
		for (int a = 0; a < args.size(); a++) {
			Argument arg = (Argument) args.get(a);
			parameter[a] = arg.getName();
		}

		ISourceElementRequestor.MethodInfo mi = new ISourceElementRequestor.MethodInfo();
		mi.parameterNames = parameter;
		mi.name = method.getName();
		mi.modifiers = method.getModifiers();
		mi.nameSourceStart = method.getNameStart();
		mi.nameSourceEnd = method.getNameEnd() - 1;
		mi.declarationStart = method.sourceStart();
		
		mi.isConstructor = mi.name.equalsIgnoreCase(CONSTRUCTOR_NAME) || (parentDeclaration instanceof ClassDeclaration && 
				mi.name.equalsIgnoreCase(((ClassDeclaration)parentDeclaration).getName()));

		this.fRequestor.enterMethod(mi);

		this.fInMethod = true;
		this.fCurrentMethod = method;
		return true;
	}

	public boolean visit(TypeDeclaration type) throws Exception {
		declarations.push(type);

		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.visit(type);
		}
		return super.visit(type);
	}

	/**
	 * Resolve class members that were defined using the @property tag  
	 * @param type declaration for wich we add the magic variables
	 */
	private void resolveMagicMembers(TypeDeclaration type) {
		if (type instanceof IPHPDocAwareDeclaration) {
			IPHPDocAwareDeclaration declaration = (IPHPDocAwareDeclaration) type;
			final PHPDocBlock doc = declaration.getPHPDoc();
			if (doc != null) {
				final PHPDocTag[] tags = doc.getTags();
				for (PHPDocTag docTag : tags) {
					final int tagKind = docTag.getTagKind();
					if (tagKind == PHPDocTag.PROPERTY || tagKind == PHPDocTag.PROPERTY_READ || tagKind == PHPDocTag.PROPERTY_WRITE) {
						final String[] split = docTag.getValue().trim().split("\\s+");
						if (split.length < 2) {
							break;
						}
						ISourceElementRequestor.FieldInfo info = new ISourceElementRequestor.FieldInfo();
						info.modifiers = Modifiers.AccPublic;
						info.name = split[1];
						SimpleReference var = new SimpleReference(docTag.sourceStart(), docTag.sourceStart() + 9, split[1]);
						info.nameSourceEnd = var.sourceEnd() - 1;
						info.nameSourceStart = var.sourceStart();
						info.declarationStart = info.nameSourceStart;
						fRequestor.enterField(info);
						fRequestor.exitField(info.nameSourceEnd);
					}
				}
			}
		}
	}

	public boolean visit(PHPFieldDeclaration declaration) throws Exception {
		ISourceElementRequestor.FieldInfo info = new ISourceElementRequestor.FieldInfo();
		info.modifiers = declaration.getModifiers();
		info.name = declaration.getName();
		SimpleReference var = declaration.getRef();
		info.nameSourceEnd = var.sourceEnd() - 1;
		info.nameSourceStart = var.sourceStart();
		info.declarationStart = declaration.getDeclarationStart();
		fRequestor.enterField(info);
		return true;
	}

	public boolean endvisit(PHPFieldDeclaration declaration) throws Exception {
		fRequestor.exitField(declaration.sourceEnd() - 1);
		return true;
	}

	public boolean visit(CallExpression call) throws Exception {
		int argsCount = 0;
		CallArgumentsList args = call.getArgs();
		if (args != null && args.getChilds() != null) {
			argsCount = args.getChilds().size();
		}
		String name = call.getName();
		if ("define".equals(name)) {//$NON-NLS-0$
			ISourceElementRequestor.FieldInfo info = new ISourceElementRequestor.FieldInfo();
			info.modifiers = Modifiers.AccConstant;
			Argument argument = (Argument) call.getArgs().getChilds().get(0);
			info.name = stripQuotes(argument.getName());
			info.nameSourceEnd = argument.sourceEnd() - 1;
			info.nameSourceStart = argument.sourceStart();
			info.declarationStart = call.sourceStart();
			fRequestor.enterField(info);
		} else {
			fRequestor.acceptMethodReference(call.getName().toCharArray(), argsCount, call.sourceStart(), call.sourceEnd());
		}
		return true;
	}

	public boolean visit(Include include) throws Exception {
		// special case for include statements; we need to cache this information in order to access it quickly:
		if (include.getExpr() instanceof Scalar) {
			Scalar filePath = (Scalar) include.getExpr();
			fRequestor.acceptMethodReference("include".toCharArray(), 0, filePath.sourceStart(), filePath.sourceEnd());
		}
		return true;
	}

	public boolean visit(ClassConstantDeclaration declaration) throws Exception {
		ISourceElementRequestor.FieldInfo info = new ISourceElementRequestor.FieldInfo();
		info.modifiers = Modifiers.AccConstant;
		ConstantReference constantName = declaration.getConstantName();
		info.name = stripQuotes(constantName.getName());
		info.nameSourceEnd = constantName.sourceEnd() - 1;
		info.nameSourceStart = constantName.sourceStart();
		info.declarationStart = declaration.sourceStart();
		fRequestor.enterField(info);
		return true;
	}

	public boolean endvisit(ClassConstantDeclaration declaration) throws Exception {
		fRequestor.exitField(declaration.sourceEnd() - 1);
		return true;
	}

	@SuppressWarnings("unchecked")
	public boolean visit(Assignment assignment) throws Exception {
		Expression left = assignment.getVariable();
		if (left instanceof FieldAccess) { // class variable ($this->a = .)
			FieldAccess fieldAccess = (FieldAccess) left;
			Expression dispatcher = fieldAccess.getDispatcher();
			if (dispatcher instanceof VariableReference && "$this".equals(((VariableReference) dispatcher).getName())) { //$NON-NLS-1$
				Expression field = fieldAccess.getField();
				if (field instanceof SimpleReference) {
					SimpleReference ref = (SimpleReference) field;
					ISourceElementRequestor.FieldInfo info = new ISourceElementRequestor.FieldInfo();
					info.modifiers = Modifiers.AccDefault;
					info.name = '$' + ref.getName();
					info.nameSourceEnd = ref.sourceEnd() - 1;
					info.nameSourceStart = ref.sourceStart();
					info.declarationStart = assignment.sourceStart();
					fRequestor.enterField(info);
					fNodes.push(assignment);
				}
			}
		} else if (left instanceof VariableReference) {
			ISourceElementRequestor.FieldInfo info = new ISourceElementRequestor.FieldInfo();
			info.modifiers = Modifiers.AccDefault;
			info.name = ((VariableReference)left).getName();
			info.nameSourceEnd = left.sourceEnd() - 1;
			info.nameSourceStart = left.sourceStart();
			info.declarationStart = assignment.sourceStart();
			fRequestor.enterField(info);
			fNodes.push(assignment);
		}
		return true;
	}

	public boolean endvisit(Assignment assignment) throws Exception {
		if (!fNodes.isEmpty() && fNodes.peek() == assignment) {
			fRequestor.exitField(assignment.sourceEnd() - 1);
			fNodes.pop();
		}
		return true;
	}

	public boolean visit(TypeReference reference) throws Exception {
		fRequestor.acceptTypeReference(reference.getName().toCharArray(), reference.sourceStart());
		return true;
	}

	public boolean visit(Statement node) throws Exception {
		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.visit(node);
		}

		String clasName = node.getClass().getName();
		if (clasName.equals(PHPFieldDeclaration.class.getName())) {
			return visit((PHPFieldDeclaration) node);
		}
		if (clasName.equals(ClassConstantDeclaration.class.getName())) {
			return visit((ClassConstantDeclaration) node);
		}
		return true;
	}

	public boolean endvisit(Statement node) throws Exception {
		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.endvisit(node);
		}

		String clasName = node.getClass().getName();
		if (clasName.equals(PHPFieldDeclaration.class.getName())) {
			return endvisit((PHPFieldDeclaration) node);
		}
		if (clasName.equals(ClassConstantDeclaration.class.getName())) {
			return endvisit((ClassConstantDeclaration) node);
		}
		return true;
	}

	public boolean visit(Expression node) throws Exception {
		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.visit(node);
		}

		String clasName = node.getClass().getName();
		if (clasName.equals(Assignment.class.getName())) {
			return visit((Assignment) node);
		}
		if (clasName.equals(TypeReference.class.getName())) {
			return visit((TypeReference) node);
		}
		if (clasName.equals(Include.class.getName())) {
			return visit((Include) node);
		}
		if (clasName.equals(PHPCallExpression.class.getName())) {
			return visit((PHPCallExpression) node);
		}
		return true;
	}

	public boolean endvisit(Expression node) throws Exception {
		for (PHPSourceElementRequestorExtension visitor : extensions) {
			visitor.endvisit(node);
		}

		String clasName = node.getClass().getName();
		if (clasName.equals(Assignment.class.getName())) {
			return endvisit((Assignment) node);
		}
		return true;
	}
}