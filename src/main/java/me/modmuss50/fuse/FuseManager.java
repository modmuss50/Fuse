package me.modmuss50.fuse;

import java.util.*;

public class FuseManager {

	static Map<String, Set<String>> mixinMap = new LinkedHashMap<>();

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
