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
					MethodNode targetMethodNode = findTargetMethodNode(mixinMethodNode.name, targetNode);
					AnnotationNode annotationNode = getAnnoation(mixinMethodNode.visibleAnnotations, MethodEdit.class);
					if (targetMethodNode != null) {
						if (getAnnoationValue(annotationNode, "location") == MethodEdit.Location.START) {
							targetMethodNode.instructions.insertBefore(findFirstInstruction(targetMethodNode), renameInstructions(removeLastReturnInsn(mixinMethodNode.instructions), mixinNode, targetNode));
						} else {
							targetMethodNode.instructions = injectAfterInsnReturn(targetMethodNode.instructions, renameInstructions(mixinMethodNode.instructions, mixinNode, targetNode));
						}

					}
				} else if (hasAnnoation(mixinMethodNode.visibleAnnotations, Inject.class)) {
					MethodNode methodInject = new MethodNode(mixinMethodNode.access, mixinMethodNode.name, mixinMethodNode.desc, mixinMethodNode.signature, null);
					methodInject.exceptions = mixinMethodNode.exceptions;
					methodInject.instructions = renameInstructions(mixinMethodNode.instructions, mixinNode, targetNode);
					targetNode.methods.add(methodInject);
				}
			}
			for (FieldNode mixinFieldNode : mixinNode.fields) {
				if (hasAnnoation(mixinFieldNode.visibleAnnotations, Inject.class)) {
					targetNode.fields.add(mixinFieldNode);
				}
			}
		}
		return writeClassToBytes(targetNode);
	}

	MethodNode findTargetMethodNode(String name, ClassNode targetNode) {
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

	InsnList removeLastReturnInsn(InsnList instructions) {
		instructions.iterator().forEachRemaining(abstractInsnNode -> {
			if (abstractInsnNode instanceof InsnNode) {
				//TODO this will break things, need to be done when necceserry (i.e only the last one?
				if (abstractInsnNode.getOpcode() == Opcodes.RETURN) {
					instructions.remove(abstractInsnNode);
				}
			}
		});
		return instructions;
	}

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

	InsnList buildConstructorInstructions(ClassNode mixinNode) {
		InsnList insnList = new InsnList();
		insnList.add(findTargetMethodNode("<init>", mixinNode).instructions);
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

	//TODO remove this
	InsnList cleanLableNode(InsnList insnList) {
		insnList.iterator().forEachRemaining(abstractInsnNode -> {
			if (abstractInsnNode instanceof LabelNode || abstractInsnNode instanceof LineNumberNode) {
				insnList.remove(abstractInsnNode);
			}
		});
		return insnList;
	}

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
