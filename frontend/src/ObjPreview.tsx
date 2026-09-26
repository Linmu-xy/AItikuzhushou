import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader.js';

type ObjPreviewProps = {
  src: string;
  label: string;
};

/** Lightweight, isolated OBJ viewer used only by the material-exam page. */
export function ObjPreview({ src, label }: ObjPreviewProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState('');

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return undefined;

    let disposed = false;
    let frame = 0;
    let renderer: THREE.WebGLRenderer | undefined;
    let controls: OrbitControls | undefined;
    let observer: ResizeObserver | undefined;
    let resetView: (() => void) | undefined;
    let handleDoubleClick: (() => void) | undefined;

    const fail = (message: string) => {
      if (disposed) return;
      setError(message);
      setState('error');
    };

    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      renderer.setSize(Math.max(host.clientWidth, 320), Math.max(host.clientHeight, 320), false);
      renderer.outputColorSpace = THREE.SRGBColorSpace;
      renderer.domElement.className = 'obj-preview-canvas';
      renderer.domElement.setAttribute('aria-label', label);
      host.appendChild(renderer.domElement);

      const scene = new THREE.Scene();
      scene.background = new THREE.Color('#eef3f1');
      const camera = new THREE.PerspectiveCamera(42, 1, 0.01, 100000);
      camera.position.set(3, 2.5, 4);

      scene.add(new THREE.HemisphereLight('#ffffff', '#78908a', 2.2));
      const keyLight = new THREE.DirectionalLight('#ffffff', 2.4);
      keyLight.position.set(4, 6, 5);
      scene.add(keyLight);
      const fillLight = new THREE.DirectionalLight('#b9d7d1', 1.1);
      fillLight.position.set(-4, 2, -3);
      scene.add(fillLight);

      controls = new OrbitControls(camera, renderer.domElement);
      controls.enableDamping = true;
      controls.dampingFactor = 0.08;
      controls.minDistance = 0.05;
      controls.maxDistance = 100000;
      controls.target.set(0, 0, 0);
      handleDoubleClick = () => resetView?.();
      renderer.domElement.addEventListener('dblclick', handleDoubleClick);

      const resize = () => {
        if (!renderer || !host) return;
        const width = Math.max(host.clientWidth, 320);
        const height = Math.max(host.clientHeight, 320);
        camera.aspect = width / height;
        camera.updateProjectionMatrix();
        renderer.setSize(width, height, false);
      };
      observer = new ResizeObserver(resize);
      observer.observe(host);
      resize();

      const loader = new OBJLoader();
      loader.load(src, object => {
        if (disposed) return;
        const material = new THREE.MeshStandardMaterial({
          color: '#3c8581',
          roughness: 0.62,
          metalness: 0.08,
          side: THREE.DoubleSide,
        });
        object.traverse(child => {
          const mesh = child as THREE.Mesh;
          if (!mesh.isMesh) return;
          mesh.material = material;
          mesh.geometry.computeVertexNormals();
        });

        const bounds = new THREE.Box3().setFromObject(object);
        const center = bounds.getCenter(new THREE.Vector3());
        const size = bounds.getSize(new THREE.Vector3());
        const radius = Math.max(size.length() * 0.55, 0.1);
        object.position.sub(center);
        scene.add(object);
        camera.near = Math.max(radius / 1000, 0.001);
        camera.far = Math.max(radius * 100, 1000);
        camera.position.set(radius * 1.35, radius * 0.95, radius * 1.55);
        camera.lookAt(0, 0, 0);
        controls?.target.set(0, 0, 0);
        controls?.update();
        const initialCameraPosition = camera.position.clone();
        resetView = () => {
          camera.position.copy(initialCameraPosition);
          controls?.target.set(0, 0, 0);
          controls?.update();
        };
        setState('ready');
      }, undefined, () => fail('三维预览文件读取失败，请重新分析资料或下载原始模型。'));

      const render = () => {
        if (disposed || !renderer) return;
        controls?.update();
        renderer.render(scene, camera);
        frame = window.requestAnimationFrame(render);
      };
      render();
    } catch (caught) {
      fail(caught instanceof Error ? caught.message : '当前浏览器不支持三维预览');
    }

    return () => {
      disposed = true;
      window.cancelAnimationFrame(frame);
      observer?.disconnect();
      controls?.dispose();
      if (handleDoubleClick) renderer?.domElement.removeEventListener('dblclick', handleDoubleClick);
      renderer?.dispose();
      renderer?.domElement.remove();
    };
  }, [label, src]);

  return (
    <div className="obj-preview" ref={hostRef} aria-busy={state === 'loading'}>
      {state === 'loading' && <div className="obj-preview-message"><span className="loading-dot" />正在加载三维预览…</div>}
      {state === 'error' && <div className="obj-preview-message obj-preview-error"><b>暂时无法预览</b><span>{error}</span></div>}
      {state === 'ready' && <span className="obj-preview-hint">拖动旋转 · 滚轮缩放 · 双击复位</span>}
    </div>
  );
}
