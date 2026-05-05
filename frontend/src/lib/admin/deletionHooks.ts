import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import { userApi } from "@/lib/user/api";

export function useDeleteSelf() {
  return useMutation({
    mutationFn: (password: string) => userApi.deleteSelf(password),
  });
}

export function useDeleteUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ publicId, adminNote }: { publicId: string; adminNote: string }) =>
      adminApi.users.delete(publicId, adminNote),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.users() });
    },
  });
}
