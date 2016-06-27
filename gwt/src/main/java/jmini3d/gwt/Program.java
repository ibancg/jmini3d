package jmini3d.gwt;

import com.googlecode.gwtgl.array.Float32Array;
import com.googlecode.gwtgl.binding.WebGLBuffer;
import com.googlecode.gwtgl.binding.WebGLProgram;
import com.googlecode.gwtgl.binding.WebGLRenderingContext;
import com.googlecode.gwtgl.binding.WebGLShader;
import com.googlecode.gwtgl.binding.WebGLTexture;
import com.googlecode.gwtgl.binding.WebGLUniformLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import jmini3d.Color4;
import jmini3d.GpuObjectStatus;
import jmini3d.Object3d;
import jmini3d.Scene;
import jmini3d.Vector3;
import jmini3d.light.AmbientLight;
import jmini3d.light.DirectionalLight;
import jmini3d.light.Light;
import jmini3d.light.PointLight;
import jmini3d.material.Material;
import jmini3d.material.PhongMaterial;
import jmini3d.shader.ShaderKey;
import jmini3d.shader.ShaderPlugin;

public class Program extends jmini3d.shader.Program {
	static final String TAG = "Program";

	int key = -1;

	WebGLProgram webGLProgram;

	int maxPointLights;
	int maxDirLights;
	Float32Array pointLightPositions;
	Float32Array pointLightColors;
	Float32Array dirLightDirections;
	Float32Array dirLightColors;

	boolean useLighting = false;
	boolean useNormals = false;
	boolean useMap = false;
	boolean useEnvMap = false;
	boolean useNormalMap = false;
	boolean useVertexColors = false;
	boolean useCameraPosition = false;
	boolean useShaderPlugins = false;

	int vertexPositionAttribLocation = -1;
	int vertexNormalAttribLocation = -1;
	int textureCoordAttribLocation = -1;
	int vertexColorAttribLocation = -1;

	// Cached values to avoid setting buffers with an already existing value
	WebGLBuffer activeVertexPosition = null;
	WebGLBuffer activeVertexNormal = null;
	WebGLBuffer activeTextureCoord = null;
	WebGLBuffer activeVertexColor = null;
	WebGLBuffer activeFacesBuffer = null;

	HashMap<String, WebGLUniformLocation> uniforms = new HashMap<>();
	// *********************** BEGIN cached values to avoid setting uniforms two times
	int map = -1;
	int envMap = -1;
	int normalMap = -1;
	float projectionMatrix[] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
	float viewMatrix[] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
	float modelMatrix[] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
	float normalMatrix[] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
	Color4 objectColor = Color4.fromFloat(-1, -1, -1, -1);
	Color4 ambientColor = Color4.fromFloat(-1, -1, -1, -1);
	Color4 diffuseColor = Color4.fromFloat(-1, -1, -1, -1);
	Color4 specularColor = Color4.fromFloat(-1, -1, -1, -1);
	Color4 ambientLightColor = Color4.fromFloat(-1, -1, -1, -1);
	float shininess = 0;
	Vector3 cameraPosition = new Vector3(0, 0, 0);
	float reflectivity;
	HashMap<String, float[]> cachedValues = new HashMap<>();
	// *********************** END

	private WebGLRenderingContext GLES20;

	boolean shaderLoaded = false;

	String vertexShaderLoaded;
	String fragmentShaderLoaded;

	public Program(WebGLRenderingContext GLES20) {
		this.GLES20 = GLES20;
	}

	@Override
	public void setUniformIfCachedValueChanged(String uniformName, float uniformValue, float[] cachedValues, int cachedValueIndex) {
		if (cachedValues[0] != uniformValue) {
			cachedValues[0] = uniformValue;
			GLES20.uniform1f(uniforms.get(uniformName), uniformValue);
		}
	}

	@Override
	public float[] getValueCache(String cacheKey, int size) {
		if (cachedValues.containsKey(cacheKey)) {
			return cachedValues.get(cacheKey);
		} else {
			float[] cachedValueFloats = new float[size];
			cachedValues.put(cacheKey, cachedValueFloats);
			return cachedValueFloats;
		}
	}

	native void log(String message) /*-{
		console.log(message);
    }-*/;

	public void init(Scene scene, Material material, ResourceLoader resourceLoader) {
		useShaderPlugins = (scene.shaderKey & material.shaderKey & ShaderKey.SHADER_PLUGIN_MASK) != 0;

		String vertexShaderName = DEFAULT_VERTEX_SHADER;
		String fragmentShaderName = DEFAULT_FRAGMENT_SHADER;

		if (useShaderPlugins) {
			for (ShaderPlugin sp : scene.shaderPlugins) {
				if (sp.getVertexShaderName() != null) {
					vertexShaderName = sp.getVertexShaderName();
				}
				if (sp.getFragmentShaderName() != null) {
					fragmentShaderName = sp.getFragmentShaderName();
				}
			}
		}

		resourceLoader.loadShader(vertexShaderName, new ResourceLoader.OnTextResourceLoaded() {
			@Override
			public void onResourceLoaded(String text) {
				vertexShaderLoaded = text;
				if (vertexShaderLoaded != null && fragmentShaderLoaded != null) {
					finishShaderLoad(scene, material);
				}
			}
		});
		resourceLoader.loadShader(fragmentShaderName, new ResourceLoader.OnTextResourceLoaded() {
			@Override
			public void onResourceLoaded(String text) {
				fragmentShaderLoaded = text;
				if (vertexShaderLoaded != null && fragmentShaderLoaded != null) {
					finishShaderLoad(scene, material);
				}
			}
		});
	}

	public void finishShaderLoad(Scene scene, Material material) {
		ArrayList<String> uniformsInit = new ArrayList<>();

		ArrayList<String> defines = new ArrayList<>();
		HashMap<String, String> definesValues = new HashMap<>();

		uniformsInit.add("projectionMatrix");
		uniformsInit.add("viewMatrix");
		uniformsInit.add("modelMatrix");
		uniformsInit.add("objectColor");

		if (material.map != null) {
			defines.add("USE_MAP");
			useMap = true;
			uniformsInit.add("map");
		}

		if (material.envMap != null) {
			defines.add("USE_ENVMAP");
			useNormals = true;
			useEnvMap = true;
			useCameraPosition = true;
			uniformsInit.add("reflectivity");
			uniformsInit.add("envMap");
		}

		if (material.useEnvMapAsMap) {
			defines.add("USE_ENVMAP_AS_MAP");
		}

		if (material.applyColorToAlpha) {
			defines.add("APPLY_COLOR_TO_ALPHA");
		}

		if (material.useVertexColors) {
			useVertexColors = true;
			defines.add("USE_VERTEX_COLORS");
		}

		maxPointLights = 0;
		maxDirLights = 0;

		if (material instanceof PhongMaterial && scene.lights.size() > 0) {
			defines.add("USE_PHONG_LIGHTING");
			uniformsInit.add("ambientColor");
			uniformsInit.add("diffuseColor");
			uniformsInit.add("specularColor");
			uniformsInit.add("shininess");
			useLighting = true;

			for (Light light : scene.lights) {

				if (light instanceof AmbientLight) {
					defines.add("USE_AMBIENT_LIGHT");
					uniformsInit.add("ambientLightColor");
				}

				if (light instanceof PointLight) {
					maxPointLights++;
				}

				if (light instanceof DirectionalLight) {
					maxDirLights++;
				}
			}

			if (maxPointLights > 0) {
				defines.add("USE_POINT_LIGHT");
				uniformsInit.add("pointLightPosition");
				uniformsInit.add("pointLightColor");
				useCameraPosition = true;

				pointLightPositions = Float32Array.create(maxPointLights * 3);
				pointLightColors = Float32Array.create(maxPointLights * 4);
			}

			if (maxDirLights > 0) {
				defines.add("USE_DIR_LIGHT");
				uniformsInit.add("dirLightDirection");
				uniformsInit.add("dirLightColor");
				useCameraPosition = true;

				dirLightDirections = Float32Array.create(maxDirLights * 3);
				dirLightColors = Float32Array.create(maxDirLights * 4);
			}

			useNormals = true;
		}

		definesValues.put("MAX_POINT_LIGHTS", String.valueOf(maxPointLights));
		definesValues.put("MAX_DIR_LIGHTS", String.valueOf(maxDirLights));

		if (useCameraPosition) {
			defines.add("USE_CAMERA_POSITION");
			uniformsInit.add("cameraPosition");
		}

		if (useNormals) {
			if (material.normalMap != null) {
				defines.add("USE_NORMAL_MAP");
				useNormalMap = true;
				useNormals = false;
				uniformsInit.add("normalMap");
				uniformsInit.add("normalMatrix");
			} else {
				defines.add("USE_NORMALS");
				uniformsInit.add("normalMatrix");
			}
		}

		if (useShaderPlugins) {
			for (ShaderPlugin sp : scene.shaderPlugins) {
				sp.addShaderDefines(defines);
				sp.addUniformNames(uniformsInit);
			}
		}

		StringBuffer vertexShaderStringBuffer = new StringBuffer();
		StringBuffer fragmentShaderStringBuffer = new StringBuffer();

		// TODO precision
		for (String d : defines) {
			vertexShaderStringBuffer.append("#define " + d + "\n");
			fragmentShaderStringBuffer.append("#define " + d + "\n");
		}

		for (String k : definesValues.keySet()) {
			vertexShaderStringBuffer.append("#define " + k + " " + definesValues.get(k) + "\n");
			fragmentShaderStringBuffer.append("#define " + k + " " + definesValues.get(k) + "\n");
		}

		vertexShaderStringBuffer.append(vertexShaderLoaded);
		fragmentShaderStringBuffer.append(fragmentShaderLoaded);

		String vertexShaderString = vertexShaderStringBuffer.toString();
		String fragmentShaderString = fragmentShaderStringBuffer.toString();

		WebGLShader vertexShader = getShader(WebGLRenderingContext.VERTEX_SHADER, vertexShaderString);
		WebGLShader fragmentShader = getShader(WebGLRenderingContext.FRAGMENT_SHADER, fragmentShaderString);

		webGLProgram = GLES20.createProgram();
		GLES20.attachShader(webGLProgram, vertexShader);
		GLES20.attachShader(webGLProgram, fragmentShader);
		GLES20.linkProgram(webGLProgram);

		if (!GLES20.getProgramParameterb(webGLProgram, WebGLRenderingContext.LINK_STATUS)) {
			throw new RuntimeException("Could not initialize shaders");
		}
		GLES20.useProgram(webGLProgram);

		for (String uniform : uniformsInit) {
			uniforms.put(uniform, GLES20.getUniformLocation(webGLProgram, uniform));
		}

		// Initialize attrib locations
		vertexPositionAttribLocation = getAndEnableAttribLocation("vertexPosition");

		GLES20.deleteShader(vertexShader);
		GLES20.deleteShader(fragmentShader);

		vertexShaderLoaded = null;
		fragmentShaderLoaded = null;
		shaderLoaded = true;
	}

	private int getAndEnableAttribLocation(String attribName) {
		int attribLocation = GLES20.getAttribLocation(webGLProgram, attribName);
		GLES20.enableVertexAttribArray(attribLocation);
		return attribLocation;
	}

	public void setSceneUniforms(Scene scene) {
		if (!shaderLoaded) {
			return;
		}

		// Reset cached attribs at the beginning of each frame
		activeVertexPosition = null;
		activeVertexNormal = null;
		activeTextureCoord = null;
		activeVertexColor = null;
		activeFacesBuffer = null;

		if (useMap && map != 0) {
			GLES20.uniform1i(uniforms.get("map"), 0);
			map = 0;
		}
		if (useEnvMap && envMap != 1) {
			GLES20.uniform1i(uniforms.get("envMap"), 1);
			envMap = 1;
		}
		if (useCameraPosition && !cameraPosition.equals(scene.camera.position)) {
			GLES20.uniform3f(uniforms.get("cameraPosition"), scene.camera.position.x, scene.camera.position.y, scene.camera.position.z);
			cameraPosition.setAllFrom(scene.camera.position);
		}
		if (useNormalMap && normalMap != 2) {
			GLES20.uniform1i(uniforms.get("normalMap"), 2);
			normalMap = 2;
		}

		if (useLighting) {
			int pointLightIndex = 0;
			int dirLightIndex = 0;

			for (int i = 0; i < scene.lights.size(); i++) {
				Light light = scene.lights.get(i);
				if (light instanceof AmbientLight) {
					setColorIfChanged("ambientLightColor", light.color, ambientLightColor);
				}

				if (light instanceof PointLight) {
					pointLightPositions.set(pointLightIndex * 3, ((PointLight) light).position.x);
					pointLightPositions.set(pointLightIndex * 3 + 1, ((PointLight) light).position.y);
					pointLightPositions.set(pointLightIndex * 3 + 2, ((PointLight) light).position.z);

					pointLightColors.set(pointLightIndex * 4, light.color.r);
					pointLightColors.set(pointLightIndex * 4 + 1, light.color.g);
					pointLightColors.set(pointLightIndex * 4 + 2, light.color.b);
					pointLightColors.set(pointLightIndex * 4 + 3, light.color.a);

					pointLightIndex++;
				}

				if (light instanceof DirectionalLight) {
					dirLightDirections.set(dirLightIndex * 3, ((DirectionalLight) light).direction.x);
					dirLightDirections.set(dirLightIndex * 3 + 1, ((DirectionalLight) light).direction.y);
					dirLightDirections.set(dirLightIndex * 3 + 2, ((DirectionalLight) light).direction.z);

					dirLightColors.set(dirLightIndex * 4, light.color.r);
					dirLightColors.set(dirLightIndex * 4 + 1, light.color.g);
					dirLightColors.set(dirLightIndex * 4 + 2, light.color.b);
					dirLightColors.set(dirLightIndex * 4 + 3, light.color.a);

					dirLightIndex++;
				}
			}
			if (maxPointLights > 0) {
				GLES20.uniform3fv(uniforms.get("pointLightPosition"), pointLightPositions);
				GLES20.uniform4fv(uniforms.get("pointLightColor"), pointLightColors);
			}
			if (maxDirLights > 0) {
				GLES20.uniform3fv(uniforms.get("dirLightDirection"), dirLightDirections);
				GLES20.uniform4fv(uniforms.get("dirLightColor"), dirLightColors);
			}
		}

		if (useShaderPlugins) {
			for (int i = 0; i < scene.shaderPlugins.size(); i++) {
				scene.shaderPlugins.get(i).setSceneUniforms(this);
			}
		}
	}

	public void drawObject(Renderer3d renderer3d, GpuUploader gpuUploader, Object3d o3d, float[] projectionMatrix, float viewMatrix[]) {
		if (!shaderLoaded) {
			return;
		}

		if (!Arrays.equals(this.projectionMatrix, projectionMatrix)) {
			GLES20.uniformMatrix4fv(uniforms.get("projectionMatrix"), false, projectionMatrix);
			System.arraycopy(projectionMatrix, 0, this.projectionMatrix, 0, 16);
		}
		if (!Arrays.equals(this.viewMatrix, viewMatrix)) {
			GLES20.uniformMatrix4fv(uniforms.get("viewMatrix"), false, viewMatrix);
			System.arraycopy(viewMatrix, 0, this.viewMatrix, 0, 16);
		}
		if (!Arrays.equals(modelMatrix, o3d.modelMatrix)) {
			GLES20.uniformMatrix4fv(uniforms.get("modelMatrix"), false, o3d.modelMatrix);
			System.arraycopy(o3d.modelMatrix, 0, modelMatrix, 0, 16);
		}
		if ((useNormals || useNormalMap) && o3d.normalMatrix != null && !Arrays.equals(normalMatrix, o3d.normalMatrix)) {
			GLES20.uniformMatrix3fv(uniforms.get("normalMatrix"), false, o3d.normalMatrix);
			System.arraycopy(o3d.normalMatrix, 0, normalMatrix, 0, 9);
		}

		GeometryBuffers buffers = gpuUploader.upload(o3d.geometry3d);
		WebGLBuffer vertexColorsBufferId = null;
		if (useVertexColors) {
			vertexColorsBufferId = gpuUploader.upload(o3d.vertexColors);
		}

		if (useMap) {
			gpuUploader.upload(renderer3d, o3d.material.map, WebGLRenderingContext.TEXTURE0);
			if ((o3d.material.map.status & GpuObjectStatus.TEXTURE_UPLOADED) == 0) {
				return;
			}
		}
		if (useEnvMap) {
			gpuUploader.upload(renderer3d, o3d.material.envMap, WebGLRenderingContext.TEXTURE1);
			if ((o3d.material.envMap.status & GpuObjectStatus.TEXTURE_UPLOADED) == 0) {
				return;
			}
		}
		if (useNormalMap) {
			gpuUploader.upload(renderer3d, o3d.material.normalMap, WebGLRenderingContext.TEXTURE2);
			if ((o3d.material.normalMap.status & GpuObjectStatus.TEXTURE_UPLOADED) == 0) {
				return;
			}
		}

		if (activeVertexPosition == null || activeVertexPosition != buffers.vertexBufferId) {
			activeVertexPosition = buffers.vertexBufferId;
			GLES20.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffers.vertexBufferId);
			GLES20.vertexAttribPointer(vertexPositionAttribLocation, 3, WebGLRenderingContext.FLOAT, false, 0, 0);
		}

		if (useNormals) {
			if (activeVertexNormal == null || activeVertexNormal != buffers.normalsBufferId) {
				activeVertexNormal = buffers.normalsBufferId;
				if (vertexNormalAttribLocation == -1) {
					vertexNormalAttribLocation = getAndEnableAttribLocation("vertexNormal");
				}
				GLES20.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffers.normalsBufferId);
				GLES20.vertexAttribPointer(vertexNormalAttribLocation, 3, WebGLRenderingContext.FLOAT, false, 0, 0);
			}
		}

		if (useMap) {
			if (activeTextureCoord == null || activeTextureCoord != buffers.uvsBufferId) {
				activeTextureCoord = buffers.uvsBufferId;
				if (textureCoordAttribLocation == -1) {
					textureCoordAttribLocation = getAndEnableAttribLocation("textureCoord");
				}
				GLES20.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, buffers.uvsBufferId);
				GLES20.vertexAttribPointer(textureCoordAttribLocation, 2, WebGLRenderingContext.FLOAT, false, 0, 0);
			}
		}

		if (useVertexColors && (vertexColorsBufferId != null)) {
			if (activeVertexColor == null || activeVertexColor != vertexColorsBufferId) {
				activeVertexColor = vertexColorsBufferId;
				if (vertexColorAttribLocation == -1) {
					vertexColorAttribLocation = getAndEnableAttribLocation("vertexColor");
				}
				GLES20.bindBuffer(WebGLRenderingContext.ARRAY_BUFFER, vertexColorsBufferId);
				GLES20.vertexAttribPointer(vertexColorAttribLocation, 4, WebGLRenderingContext.FLOAT, false, 0, 0);
			}
		}

		if (activeFacesBuffer == null || activeFacesBuffer != buffers.facesBufferId) {
			GLES20.bindBuffer(WebGLRenderingContext.ELEMENT_ARRAY_BUFFER, buffers.facesBufferId);
			activeFacesBuffer = buffers.facesBufferId;
		}

		if (!objectColor.equals(o3d.material.color)) {
			GLES20.uniform4f(uniforms.get("objectColor"), o3d.material.color.r, o3d.material.color.g, o3d.material.color.b, o3d.material.color.a);
			objectColor.setAllFrom(o3d.material.color);
		}
		if (useMap) {
			WebGLTexture mapTextureId = gpuUploader.textures.get(o3d.material.map);
			if (renderer3d.mapTextureId == null || renderer3d.mapTextureId != mapTextureId) {
				if (renderer3d.activeTexture != WebGLRenderingContext.TEXTURE0) {
					GLES20.activeTexture(WebGLRenderingContext.TEXTURE0);
					renderer3d.activeTexture = WebGLRenderingContext.TEXTURE0;
				}
				GLES20.bindTexture(WebGLRenderingContext.TEXTURE_2D, mapTextureId);
				renderer3d.mapTextureId = mapTextureId;
			}
		}
		if (useEnvMap) {
			if (reflectivity != o3d.material.reflectivity) {
				GLES20.uniform1f(uniforms.get("reflectivity"), o3d.material.reflectivity);
				reflectivity = o3d.material.reflectivity;
			}
			WebGLTexture envMapTextureId = gpuUploader.cubeMapTextures.get(o3d.material.envMap);
			if (renderer3d.envMapTextureId == null || renderer3d.envMapTextureId != envMapTextureId) {
				if (renderer3d.activeTexture != WebGLRenderingContext.TEXTURE1) {
					GLES20.activeTexture(WebGLRenderingContext.TEXTURE1);
					renderer3d.activeTexture = WebGLRenderingContext.TEXTURE1;
				}
				GLES20.bindTexture(WebGLRenderingContext.TEXTURE_CUBE_MAP, envMapTextureId);
				renderer3d.envMapTextureId = envMapTextureId;
			}
		}
		if (useNormalMap) {
			WebGLTexture normalMapTextureId = gpuUploader.textures.get(o3d.material.normalMap);
			if (renderer3d.normalMapTextureId != normalMapTextureId) {
				if (renderer3d.activeTexture != WebGLRenderingContext.TEXTURE2) {
					GLES20.activeTexture(WebGLRenderingContext.TEXTURE2);
					renderer3d.activeTexture = WebGLRenderingContext.TEXTURE2;
				}
				GLES20.bindTexture(WebGLRenderingContext.TEXTURE_2D, gpuUploader.textures.get(o3d.material.normalMap));
				renderer3d.normalMapTextureId = normalMapTextureId;
			}
		}
		if (useLighting) {
			setColorIfChanged("ambientColor", ((PhongMaterial) o3d.material).ambient, ambientColor);
			setColorIfChanged("diffuseColor", ((PhongMaterial) o3d.material).diffuse, diffuseColor);
			setColorIfChanged("specularColor", ((PhongMaterial) o3d.material).specular, specularColor);
			if (shininess != ((PhongMaterial) o3d.material).shininess) {
				shininess = ((PhongMaterial) o3d.material).shininess;
				GLES20.uniform1f(uniforms.get("shininess"), shininess);
			}
		}
		GLES20.drawElements(WebGLRenderingContext.TRIANGLES, o3d.geometry3d.facesLength, WebGLRenderingContext.UNSIGNED_SHORT, 0);
	}

	private void setColorIfChanged(String uniform, Color4 newColor, Color4 lastColor) {
		if (!newColor.equals(lastColor)) {
			GLES20.uniform4f(uniforms.get(uniform), newColor.r, newColor.g, newColor.b, newColor.a);
			lastColor.setAllFrom(newColor);
		}
	}

	private WebGLShader getShader(int type, String source) {
		WebGLShader shader = GLES20.createShader(type);

		GLES20.shaderSource(shader, source);
		GLES20.compileShader(shader);

		if (!GLES20.getShaderParameterb(shader, WebGLRenderingContext.COMPILE_STATUS)) {
			throw new RuntimeException(GLES20.getShaderInfoLog(shader));
		}
		return shader;
	}
}
