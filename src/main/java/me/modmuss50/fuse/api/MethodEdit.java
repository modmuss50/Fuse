package me.modmuss50.fuse.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.METHOD, ElementType.CONSTRUCTOR })
@Retention(RetentionPolicy.RUNTIME)
public @interface MethodEdit {

	String value();

	 Location location() default Location.START;


	public enum Location {
		START,
		END
	}

}
