import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { getAllCameras, getCameraInfo, getCameraStatus, getLiveViewImage, updateCamera } from '../api/cameraApi';
import CameraList from './CameraList';

vi.mock('../api/cameraApi', () => ({
  getAllCameras: vi.fn(),
  createCamera: vi.fn(),
  updateCamera: vi.fn(),
  deleteCamera: vi.fn(),
  getCameraInfo: vi.fn(),
  getCameraStatus: vi.fn(),
  getLiveViewImage: vi.fn(),
}));

afterEach(cleanup);

it('waits for an in-flight preview lease before saving the camera profile', async () => {
  const profile = {
    id: 'mock-1',
    name: 'Practice camera',
    backendType: 'mock',
    defaultStepSize: 10,
    maxFrames: 50,
    movementThreshold: 0.02,
  };
  const renamed = { ...profile, name: 'Mock studio camera', host: '' };
  const preview = Promise.withResolvers();
  getAllCameras.mockResolvedValueOnce([profile]).mockResolvedValueOnce([renamed]);
  getCameraInfo.mockResolvedValue({ model: 'mock' });
  getCameraStatus.mockResolvedValue({ connected: true });
  getLiveViewImage.mockReturnValueOnce(preview.promise);
  updateCamera.mockResolvedValue(renamed);

  render(<MemoryRouter><CameraList /></MemoryRouter>);
  await waitFor(() => expect(getLiveViewImage).toHaveBeenCalledWith(profile.id));

  fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: renamed.name } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  expect(updateCamera).not.toHaveBeenCalled();

  await act(async () => { preview.resolve('data:image/png;base64,AAAA'); });
  expect(updateCamera).toHaveBeenCalledWith(profile.id, renamed);
  expect(await screen.findByRole('img', { name: 'Mock studio camera live view' })).toBeVisible();
});
