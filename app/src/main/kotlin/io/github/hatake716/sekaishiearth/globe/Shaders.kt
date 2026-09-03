package io.github.hatake716.sekaishiearth.globe

import android.opengl.GLES20
import android.util.Log

object Shaders {
    /** 球面パッチ: 頂点は (u,v)∈[0,1]²。緯度経度はユニフォームで与え、シェーダで球面座標に変換する。 */
    const val TILE_VS = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform vec4 uLatLon;   // lat0(北端), lon0(西端), dLat, dLon (rad)
        uniform vec4 uUv;       // offsetU, offsetV, scaleU, scaleV
        attribute vec2 aUv;
        varying vec2 vTex;
        varying vec3 vNormal;
        varying vec3 vWorld;
        void main() {
            float lat = uLatLon.x - aUv.y * uLatLon.z;
            float lon = uLatLon.y + aUv.x * uLatLon.w;
            float cl = cos(lat);
            vec3 p = vec3(cl * sin(lon), sin(lat), cl * cos(lon));
            vTex = uUv.xy + aUv * uUv.zw;
            vNormal = normalize((uModel * vec4(p, 0.0)).xyz);
            vWorld = (uModel * vec4(p, 1.0)).xyz;
            gl_Position = uMvp * vec4(p, 1.0);
        }
    """

    const val TILE_FS = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform vec3 uLightDir;
        uniform vec3 uEye;
        varying vec2 vTex;
        varying vec3 vNormal;
        varying vec3 vWorld;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uEye - vWorld);
            vec4 c = texture2D(uTex, vTex);
            float diff = max(dot(n, uLightDir), 0.0);
            float light = 0.45 + 0.6 * diff;
            float fres = pow(1.0 - max(dot(n, v), 0.0), 3.0);
            vec3 col = c.rgb * light + vec3(0.25, 0.45, 0.9) * fres * 0.5;
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** 大気のにじみ: 画面に正対するビルボード四角形に放射状グラデーション。 */
    const val GLOW_VS = """
        uniform mat4 uMvp;
        uniform vec3 uRight;
        uniform vec3 uUp;
        uniform float uSize;
        attribute vec2 aPos;
        varying vec2 vPos;
        void main() {
            vec3 p = (uRight * aPos.x + uUp * aPos.y) * uSize;
            vPos = aPos;
            gl_Position = uMvp * vec4(p, 1.0);
        }
    """

    const val GLOW_FS = """
        precision mediump float;
        uniform float uInner;   // 地球の見かけ半径 / uSize
        varying vec2 vPos;
        void main() {
            float r = length(vPos);
            float a = 1.0 - smoothstep(uInner, 1.0, r);
            a = a * a;
            float inside = 1.0 - smoothstep(uInner * 0.97, uInner, r);
            a = a * (1.0 - inside);
            gl_FragColor = vec4(0.35, 0.6, 1.0, 1.0) * a * 0.9;
        }
    """

    /** 星空: スクリーン座標の点 */
    const val STAR_VS = """
        uniform vec2 uViewport;
        attribute vec3 aPos;   // x, y (0..1 相対), size
        void main() {
            vec2 ndc = vec2(aPos.x * 2.0 - 1.0, 1.0 - aPos.y * 2.0);
            gl_Position = vec4(ndc, 0.999, 1.0);
            gl_PointSize = aPos.z;
        }
    """

    const val STAR_FS = """
        precision mediump float;
        void main() {
            float d = length(gl_PointCoord - vec2(0.5));
            float a = smoothstep(0.5, 0.15, d);
            gl_FragColor = vec4(0.9, 0.93, 1.0, a * 0.9);
        }
    """

    /** マーカー: スクリーン座標の点スプライト。色と大きさは頂点属性。 */
    const val MARKER_VS = """
        uniform vec2 uViewport;
        attribute vec4 aPos;     // x, y (px), size (px), style (0=dot, 1=pin, 2=selected)
        attribute vec4 aColor;
        varying vec4 vColor;
        varying float vStyle;
        void main() {
            vec2 ndc = vec2(aPos.x / uViewport.x * 2.0 - 1.0, 1.0 - aPos.y / uViewport.y * 2.0);
            gl_Position = vec4(ndc, 0.0, 1.0);
            gl_PointSize = aPos.z;
            vColor = aColor;
            vStyle = aPos.w;
        }
    """

    const val MARKER_FS = """
        precision mediump float;
        varying vec4 vColor;
        varying float vStyle;
        void main() {
            vec2 p = gl_PointCoord - vec2(0.5);
            float d = length(p);
            if (vStyle < 0.5) {
                float a = smoothstep(0.5, 0.3, d);
                gl_FragColor = vec4(vColor.rgb, a * vColor.a);
            } else {
                float edge = smoothstep(0.5, 0.43, d);
                float ring = smoothstep(0.43, 0.38, d);
                float core = smoothstep(0.2, 0.14, d);
                vec3 col = mix(vec3(1.0), vColor.rgb, ring);
                col = mix(col, vec3(1.0), core * 0.9);
                if (vStyle > 1.5) { col = mix(col, vec3(1.0), 0.35); }
                gl_FragColor = vec4(col, edge * vColor.a);
            }
        }
    """

    /** ラベル: スクリーン座標のテクスチャ付き四角形 */
    const val LABEL_VS = """
        uniform vec2 uViewport;
        uniform vec4 uRect;   // x, y, w, h (px)
        attribute vec2 aUv;
        varying vec2 vUv;
        void main() {
            vec2 px = uRect.xy + aUv * uRect.zw;
            vec2 ndc = vec2(px.x / uViewport.x * 2.0 - 1.0, 1.0 - px.y / uViewport.y * 2.0);
            gl_Position = vec4(ndc, 0.0, 1.0);
            vUv = aUv;
        }
    """

    const val LABEL_FS = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform float uAlpha;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTex, vUv);
            gl_FragColor = vec4(c.rgb * uAlpha, c.a * uAlpha);
        }
    """

    fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("program link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            Log.e("Shaders", "compile failed: $log\n$src")
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader compile failed: $log")
        }
        return s
    }
}
