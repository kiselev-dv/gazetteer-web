package me.osm.gazetteer.web.api.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.restexpress.Request;


/**
 * Request headers parsing utilities
 * */
public final class RequestUtils {
	
	/**
	 * Parse header value as {@link Set}
	 * 
	 * @param request REST Express request
	 * @param header header name
	 * 
	 * @return Set of header values or empty Set
	 * */
	public static Set<String> getSet(Request request, String header) {
		Set<String> types = new HashSet<String>();
		List<String> t = request.getHeaders(header);
		if(t != null) {
			for(String s : t) {
				types.addAll(Arrays.asList(StringUtils.split(s, ", []\"\'")));
			}
		}
		return types;
	}

	/**
	 * Parse header value as {@link List}
	 * 
	 * @param request REST Express request
	 * @param header header name
	 * 
	 * @return List of header values or empty List
	 * */
	public static List<String> getList(Request request, String header) {
		List<String> result = new ArrayList<String>();
		List<String> t = request.getHeaders(header);
		if(t != null) {
			for(String s : t) {
				result.addAll(Arrays.asList(StringUtils.split(s, ", []\"\'")));
			}
		}
		return result;
	}
	
	/**
	 * Parse provided header as double.
	 * 
	 * Returns null if there is no such header or it can't be parsed as double
	 * 
	 * @param header header name
	 * @param request REST Express request
	 * 
	 * @return value of header parsed
	 * */
	public static Double getDoubleHeader(String header, Request request) {
		String valString = request.getHeader(header);
		if(valString != null) {
			try{
				return Double.parseDouble(valString);
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
		
		return null;
	}

	/**
	 * Parses boolean request header.
	 * 
	 * If header is absent, returns default value.
	 * 
	 * <pre>
	 * request	def     result
	 * null		true    true
	 * null		false   false
	 * true		*		true
	 * false	*		false
	 * asd		true	true
	 * xzc		false	false		
	 * 
	 * </pre>
	 * */
	public static boolean getBooleanHeader(Request request,
			String header, boolean def) {

		String head = request.getHeader(header);
		if(header == null) {
			return def;
		}

		return def ?  (! "false".equalsIgnoreCase(head) ) : ( "true".equalsIgnoreCase(head) );
	}

	public static <E extends Enum<E>> E getEnumHeader(Request request, String header, Class<E> clazz,  E defaultValue) {
		
		String hv = request.getHeader(header);
		if(hv != null) {
			try {
				return (E)Enum.valueOf(clazz, StringUtils.upperCase(hv));
			}
			catch (IllegalArgumentException e) {
				return defaultValue;
			}
		}
		
		return defaultValue;
	}
	
}
