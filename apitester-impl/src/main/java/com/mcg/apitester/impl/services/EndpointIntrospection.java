package com.mcg.apitester.impl.services;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.javaruntype.type.TypeParameter;
import org.javaruntype.type.Types;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.fasterxml.classmate.MemberResolver;
import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.classmate.members.ResolvedMethod;
import com.mcg.apitester.api.annotations.ApiDescription;
import com.mcg.apitester.api.annotations.ApiError;
import com.mcg.apitester.api.annotations.ApiErrors;
import com.mcg.apitester.api.annotations.ApiIgnore;
import com.mcg.apitester.impl.entities.ApiReturnStatus;
import com.mcg.apitester.impl.entities.MethodInfo;
import com.mcg.apitester.impl.entities.ParameterInfo;
import com.mcg.apitester.impl.entities.ParameterInfo.PARAM_TYPE;

public class EndpointIntrospection {
	
	private static ParameterInfo getInfo(ResolvedType resolvedType, MethodParameter mp, String paramName) {
		ParameterInfo out = new ParameterInfo();
		if (resolvedType.getArrayElementType() != null) {
			out.setCollection(true);
			out.setType(resolvedType.getArrayElementType().getErasedType().getCanonicalName());
			out.setTypeShort(resolvedType.getArrayElementType().getErasedType().getSimpleName());
		} else if (Collection.class.isAssignableFrom(resolvedType.getErasedType())) {
			out.setCollection(true);
			if (resolvedType.getTypeParameters() != null && resolvedType.getTypeParameters().size() == 1) {
				out.setType(resolvedType.getTypeParameters().get(0).getErasedType().getCanonicalName());
				out.setTypeShort(resolvedType.getTypeParameters().get(0).getErasedType().getSimpleName());
			}
		} else {
			out.setCollection(false);
			out.setType(resolvedType.getErasedType().getCanonicalName());
			out.setTypeShort(resolvedType.getErasedType().getSimpleName());
		}
		
		try {
			out.setObject(ObjectIntrospection.createMap(Class.forName(out.getType()), new ArrayList<>()));
		} catch (Exception e) {
		}
		
		if(mp!=null && mp.getParameterName()!=null) {
			out.setName(mp.getParameterName());
		} else {
			out.setName(paramName);
		}
			
		if(mp==null) {
			out.setParamType(PARAM_TYPE.RETURN);
		} else {
			if(mp.getParameterAnnotation(PathVariable.class)!=null) {
				out.setParamType(PARAM_TYPE.PATH);
				out.setRequired(true);
			} else if (mp.getParameterAnnotation(RequestParam.class)!=null) {
				out.setParamType(PARAM_TYPE.PATH);
				out.setRequired(mp.getParameterAnnotation(RequestParam.class).required());
				out.setDefaultValue(mp.getParameterAnnotation(RequestParam.class).defaultValue());
			} else if (mp.getParameterAnnotation(RequestBody.class)!=null) {
				out.setParamType(PARAM_TYPE.BODY);
				out.setRequired(mp.getParameterAnnotation(RequestBody.class).required());
			} else {
				return null;
			}
		}
		
		return out;
	}
	
	private static ResolvedMethod getResolvedMethod(Class<?> c, Method m) {
		TypeResolver typeResolver = new TypeResolver();
		MemberResolver memberResolver = new MemberResolver(typeResolver);
		ResolvedType resolvedType = typeResolver.resolve(c);
		ResolvedTypeWithMembers resolvedTypeWithMembers = memberResolver.resolve(resolvedType, null, null);
		for (ResolvedMethod rm : resolvedTypeWithMembers.getMemberMethods()) {
			if (rm.getRawMember().toGenericString().compareTo(m.toGenericString()) == 0) {
				return rm;
			}
		}
		return null;
	}
	
	
	public static ParameterInfo getReturnTypeInfo(Class<?> c, Method m) {
		ResolvedMethod rm = getResolvedMethod(c, m);
		if(rm==null) return null;
		ResolvedType rr = rm.getReturnType();
		if(rr==null) {
			return new ParameterInfo(false,"void","void");
		} else {
			return getInfo(rr,null,null);  
		}
	}
	
	public static List<ParameterInfo> getInfo(Class<?> c, Method m, MethodParameter[] params) {
		List<ParameterInfo> out = new ArrayList<>();
		ResolvedMethod rm = getResolvedMethod(c, m);
		if(rm==null) return null;
		
		String[] parameterNames = new LocalVariableTableParameterNameDiscoverer().getParameterNames(m); 
		
		for(int i=0;i < rm.getArgumentCount();i++) {
			if(
					!m.getParameters()[i].isAnnotationPresent(RequestBody.class) && 
					!m.getParameters()[i].isAnnotationPresent(PathVariable.class) && 
					!m.getParameters()[i].isAnnotationPresent(RequestParam.class)
					
			) {
				continue;
			}

			ParameterInfo pi = getInfo(rm.getArgumentType(i),params[i],parameterNames[i]);
			
			if(m.getParameters()[i].isAnnotationPresent(ApiDescription.class)) {
				pi.setDescription(getDescription(c, m.getParameters()[i].getAnnotation(ApiDescription.class)));
			}
			if(pi!=null) {
				out.add(pi);
			}
		}
		return out;
	}
	
	public static String getDescription(Class<?> clazz, ApiDescription apiDescription) {
		String x = apiDescription.value();
		if(apiDescription.file()!=null) {
		}
		return x;
	}
	
	public static MethodInfo getMethodInfo(Class<?> c, Method m, MethodParameter[] params) {
		if(c.isAnnotationPresent(ApiIgnore.class)) return null;
		if(m.isAnnotationPresent(ApiIgnore.class)) return null;
		MethodInfo mi = new MethodInfo(
				c.getName(),
				m.getName(),
				getReturnTypeInfo(c, m), 
				getInfo(c, m, params)
			);
		if(m.getAnnotation(ApiDescription.class)!=null) {
			mi.setDescription(getDescription(c,m.getAnnotation(ApiDescription.class)));
		}
		mi.setReturnStatus(returnStatusFromAnnotations(c, m));
		return mi;
	}
	
	
	public static List<ApiReturnStatus> returnStatusFromAnnotations(Class clazz, Method m) {
		List<ApiReturnStatus> out = new ArrayList<>();
		ResponseStatus rs = m.getAnnotation(ResponseStatus.class);
		ApiReturnStatus aes = new ApiReturnStatus();
		aes.setDef(true);
		if(rs!=null) {
			aes.setStatus(rs.code().value()); 
			aes.setName(rs.code().name());
		} else {
			aes.setStatus(200); 
			aes.setName("OK");
		}
		out.add(aes);
		
		if(clazz.isAnnotationPresent(ApiErrors.class)) {
			for(ApiError ae : ((ApiErrors)clazz.getAnnotation(ApiErrors.class)).value()) {
				ApiReturnStatus ars = new ApiReturnStatus();
				ars.setStatus(ae.value().value());
				ars.setName(ae.value().name());
				ars.setDescription(ae.description());
				out.add(ars);
			}
		}
		if(m.isAnnotationPresent(ApiErrors.class)) {
			for(ApiError ae : ((ApiErrors)m.getAnnotation(ApiErrors.class)).value()) {
				ApiReturnStatus ars = new ApiReturnStatus();
				ars.setStatus(ae.value().value());
				ars.setName(ae.value().name());
				ars.setDescription(ae.description());
				out.add(ars);
			}
		}
		
		for(Class<?> ec : m.getExceptionTypes()) {
			// theoretically, exceptions could be parsed .... 
		}
		
		
		return out;
	}
	
	
}