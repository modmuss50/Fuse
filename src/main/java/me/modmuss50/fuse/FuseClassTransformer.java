package me.modmuss50.fuse;

import me.modmuss50.fuse.api.Inject;
import me.modmuss50.fuse.api.MethodEdit;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by modmuss50 on 05/07/2017.
 */
public class FuseClassTransformer implements IClassTransformer {
	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {
		Set<String> mixinNames = FuseManager.getMixinsForClass(name);
		if (mixinNames.isEmpty()) {
			return basicClass;
		}
		ClassNode targetNode = readClassFromBytes(basicClass);
		System.out.println("Found " + mixinNames.size() + " mixins for " + name);
		Set<ClassNode> mixinNodes = new LinkedHashSet<>();
		mixinNames.forEach(s -> {
			try {
				mixinNodes.add(readClassFromBytes(Launch.classLoader.getClassBytes(s)));
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		for (ClassNode mixinNode : mixinNodes) {
			for (MethodNode mixinMethodNode : mixinNode.methods) {
				if (hasAnnoation(mixinMethodNode.visibleAnnotations, MethodEdit.class)) {
					MethodNode targetMethodNode = findMethodNode(mixinMethodNode.name, targetNode);
					AnnotationNode annotationNode = getAnnoation(mixinMethodNode.visibleAnnotations, MethodEdit.class);
					if (targetMethodNode != null) {
						if (getAnnoationValue(annotationNode, "location") == MethodEdit.Location.START) {
							//Copys the methods instructions into the start of the class.
							targetMethodNode.instructions.insertBefore(findFirstInstruction(targetMethodNode), renameInstructions(removeLastReturnInsn(mixinMethodNode.instructions), mixinNode, targetNode));
						} else {
							//Copys the mixin method instrcustions just before the last return
							targetMethodNode.instructions = injectAfterInsnReturn(targetMethodNode.instructions, renameInstructions(mixinMethodNode.instructions, mixinNode, targetNode));
						}

					}
				} else if (hasAnnoation(mixinMethodNode.visibleAnnotations, Inject.class)) { //Injects methods into the target class
					MethodNode methodInject = new MethodNode(mixinMethodNode.access, mixinMethodNode.name, mixinMethodNode.desc, mixinMethodNode.signature, null);
					methodInject.exceptions = mixinMethodNode.exceptions;
					methodInject.instructions = renameInstructions(mixinMethodNode.instructions, mixinNode, targetNode);
					targetNode.methods.add(methodInject);
				}
			}
			//Copys all fields with the Inject annoation over
			//TODO copy the field value over from the constructor
			for (FieldNode mixinFieldNode : mixinNode.fields) {
				if (hasAnnoation(mixinFieldNode.visibleAnnotations, Inject.class)) {
					targetNode.fields.add(mixinFieldNode);
				}
			}
			//Copys the interfaces over to the target
			for(String mixinInterface : mixinNode.interfaces){
				if(!targetNode.interfaces.contains(mixinInterface)){
					targetNode.interfaces.add(mixinInterface);
				}
			}
		}
		return writeClassToBytes(targetNode);
	}

	//Finds method node in clsss //TODO check desc
	MethodNode findMethodNode(String name, ClassNode targetNode) {
		for (MethodNode node : targetNode.methods) {
			if (node.name.equals(name)) {
				return node;
			}
		}
		return null;
	}

	AbstractInsnNode findFirstInstruction(MethodNode method) {
		return getOrFindInstruction(method.instructions.getFirst());
	}

	AbstractInsnNode getOrFindInstruction(AbstractInsnNode firstInsnToCheck) {
		return getOrFindInstruction(firstInsnToCheck, false);
	}

	AbstractInsnNode getOrFindInstruction(AbstractInsnNode firstInsnToCheck, boolean reverseDirection) {
		for (AbstractInsnNode instruction = firstInsnToCheck; instruction != null; instruction = reverseDirection ? instruction.getPrevious() : instruction.getNext()) {
			if (instruction.getType() != AbstractInsnNode.LABEL && instruction.getType() != AbstractInsnNode.LINE)
				return instruction;
		}
		return null;
	}

	//Removes the last return instuction from the InsnList
	InsnList removeLastReturnInsn(InsnList instructions) {
		instructions.remove(findLastReturnInsn(instructions));
		return instructions;
	}

	//Finds the last return InsnNode, is this the right way to do it?
	AbstractInsnNode findLastReturnInsn(InsnList instructions) {
		final AbstractInsnNode[] returnValue = { null };
		instructions.iterator().forEachRemaining(abstractInsnNode -> {
			if (abstractInsnNode instanceof InsnNode) {
				//TODO this will break things, need to be done when necceserry (i.e only the last one?
				if (abstractInsnNode.getOpcode() == Opcodes.RETURN) {
					returnValue[0] = abstractInsnNode;
				}
			}
		});
		return returnValue[0];
	}

	//Injects an InsnList into the end of another InsnList
	//TODO this doesnt feel right at all
	InsnList injectAfterInsnReturn(InsnList targetList, InsnList sourceList) {
		AbstractInsnNode lastReturn = null;
		for (AbstractInsnNode abstractInsnNode : targetList.toArray()) {
			if (abstractInsnNode instanceof InsnNode) {
				if (abstractInsnNode.getOpcode() == Opcodes.RETURN) {
					lastReturn = abstractInsnNode;
				}
			}
		}
		targetList.insertBefore(lastReturn, sourceList);
		targetList.remove(lastReturn);
		return targetList;
	}

	//Renames the method and field calls from the mixin class to the target class
	InsnList renameInstructions(InsnList list, ClassNode mixinNode, ClassNode targetNode) {
		list.iterator().forEachRemaining(abstractInsnNode -> {
			if (abstractInsnNode instanceof FieldInsnNode) {
				((FieldInsnNode) abstractInsnNode).owner = ((FieldInsnNode) abstractInsnNode).owner.replace(mixinNode.name.replace(".", "/"), targetNode.name.replace(".", "/"));
			}
			if (abstractInsnNode instanceof MethodInsnNode) {
				((MethodInsnNode) abstractInsnNode).owner = ((MethodInsnNode) abstractInsnNode).owner.replace(mixinNode.name.replace(".", "/"), targetNode.name.replace(".", "/"));
			}
		});
		return list;
	}

	//My amtempt at building an InsnList for the field values
	//TODO fix me
	InsnList buildConstructorInstructions(ClassNode mixinNode) {
		InsnList insnList = new InsnList();
		insnList.add(findMethodNode("<init>", mixinNode).instructions);
		cleanLableNode(insnList);
		final boolean[] foundInitCall = { false };
		insnList.iterator().forEachRemaining(abstractInsnNode -> {
			if(abstractInsnNode instanceof MethodInsnNode){
				if(((MethodInsnNode) abstractInsnNode).name.equals("<init>")){
					foundInitCall[0] = true;
				}
			}
			if (!foundInitCall[0]) {
				insnList.remove(abstractInsnNode);
			}
		});

		return removeLastReturnInsn(insnList);
	}

	//I dont even think this is a good idea, so I should remove it
	InsnList cleanLableNode(InsnList insnList) {
		insnList.iterator().forEachRemaining(abstractInsnNode -> {
			if (abstractInsnNode instanceof LabelNode || abstractInsnNode instanceof LineNumberNode) {
				insnList.remove(abstractInsnNode);
			}
		});
		return insnList;
	}

	//Gets an annoation
	AnnotationNode getAnnoation(List<AnnotationNode> annotationNodeList, Class annoationClass) {
		if (annotationNodeList == null || annotationNodeList.isEmpty()) {
			return null;
		}
		for (AnnotationNode node : annotationNodeList) {
			if (node.desc.equals(Type.getDescriptor(annoationClass))) {
				return node;
			}
		}
		return null;
	}

	boolean hasAnnoation(List<AnnotationNode> annotationNodeList, Class annoationClass) {
		return getAnnoation(annotationNodeList, annoationClass) != null;
	}

	//Gets the value for an annoation
	Object getAnnoationValue(AnnotationNode node, String keyName) {
		for (int i = 0; i < node.values.size() - 1; i++) {
			Object key = node.values.get(i);
			Object value = node.values.get(i + 1);
			if (key instanceof String && key.equals(keyName)) {
				return value;
			}
		}
		return null;
	}

	public static ClassNode readClassFromBytes(byte[] bytes) {
		ClassNode classNode = new ClassNode();
		ClassReader classReader = new ClassReader(bytes);
		classReader.accept(classNode, 0);
		return classNode;
	}

	public static byte[] writeClassToBytes(ClassNode classNode) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		classNode.accept(writer);
		return writer.toByteArray();
	}

}
