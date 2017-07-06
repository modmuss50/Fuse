package me.modmuss50.fuse;

import me.modmuss50.fuse.api.Mixin;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.util.*;

public class FuseManager {

	static Map<String, Set<String>> mixinMap = new LinkedHashMap<>();

	public static void registerMixin(String mixinClass){
		try {
			ClassNode mixinNode = FuseClassTransformer.readClassFromBytes(Launch.classLoader.getClassBytes(mixinClass));
			AnnotationNode mixinAnnotationNode = FuseClassTransformer.getAnnoation(mixinNode.visibleAnnotations, Mixin.class);
			Type mixinAnnoationObject = (Type) FuseClassTransformer.getAnnoationValue(mixinAnnotationNode, "value");
			registerMixin(mixinAnnoationObject.getClassName(), mixinClass);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void registerMixin(String targetClass, String mixinClass){
		if(mixinMap.containsKey(targetClass)){
			mixinMap.get(targetClass).add(mixinClass);
		} else {
			Set<String> mixinSet = new LinkedHashSet<>();
			mixinSet.add(mixinClass);
			mixinMap.put(targetClass, mixinSet);
		}
	}

	public static Set<String> getMixinsForClass(String className){
		if(!mixinMap.containsKey(className)){
			return Collections.emptySet();
		}
		return mixinMap.get(className);
	}

}
