/*
 * sparse-image-probe: can an IOSurface's texture (a LINEAR image over scattered guest pages) be made without a CPU
 * view on this phone? reims makes the guest's pages of such a surface contiguous for the CPU (a view: one kernel
 * mapping per 16 KiB page) and imports that view as host memory (APK-PLAN §6.19). Here: a linear BGRA8 image of a
 * full iPhone screen (1179x2556) with VK_IMAGE_CREATE_SPARSE_BINDING_BIT, its opaque memory bound piece by piece,
 * scattered and out of order, to one host-pointer import; then the GPU fills it (vkCmdClearColorImage of a part,
 * vkCmdCopyBufferToImage of a pattern) and the host checks that every byte landed at the image's linear offset in
 * the piece it was bound to.
 * Build in Termux: clang -O2 -o sparse-image-probe sparse-image-probe.c -lvulkan
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <vulkan/vulkan.h>

#define CHECK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { printf("%s: %d\n", #x, r_); return 1; } } while (0)

int main(int argc, char **argv)
{
    uint32_t W = 1179, H = 2556;
    VkFormat fmt = argc > 1 && argv[1][0] == '6' ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_B8G8R8A8_UNORM;
    uint32_t bpp = fmt == VK_FORMAT_R16G16B16A16_SFLOAT ? 8 : 4;
    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_3 };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    VkInstance inst;
    CHECK(vkCreateInstance(&ici, NULL, &inst));
    uint32_t n = 1;
    VkPhysicalDevice pd;
    vkEnumeratePhysicalDevices(inst, &n, &pd);
    VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                              | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    for (int tiling = 0; tiling < 2; tiling++) {
        VkImageFormatProperties ifp;
        VkResult r = vkGetPhysicalDeviceImageFormatProperties(pd, fmt, VK_IMAGE_TYPE_2D,
                                                              tiling ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_OPTIMAL,
                                                              usage, VK_IMAGE_CREATE_SPARSE_BINDING_BIT, &ifp);
        printf("%s %s image with SPARSE_BINDING: %s (max %ux%u)\n", bpp == 8 ? "RGBA16F" : "BGRA8",
               tiling ? "LINEAR" : "OPTIMAL", r == VK_SUCCESS ? "supported" : "NOT supported",
               r == VK_SUCCESS ? ifp.maxExtent.width : 0, r == VK_SUCCESS ? ifp.maxExtent.height : 0);
    }
    uint32_t fam = 0;
    float prio = 1;
    VkDeviceQueueCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = fam,
                                    .queueCount = 1, .pQueuePriorities = &prio };
    const char *ext[] = { "VK_EXT_external_memory_host", "VK_KHR_external_memory" };
    VkPhysicalDeviceFeatures en = { .sparseBinding = VK_TRUE };
    VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1,
                               .pQueueCreateInfos = &qci, .enabledExtensionCount = 2, .ppEnabledExtensionNames = ext,
                               .pEnabledFeatures = &en };
    VkDevice dev;
    CHECK(vkCreateDevice(pd, &dci, NULL, &dev));
    VkQueue q;
    vkGetDeviceQueue(dev, fam, 0, &q);
    VkImageCreateInfo imci = { .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .flags = VK_IMAGE_CREATE_SPARSE_BINDING_BIT,
                               .imageType = VK_IMAGE_TYPE_2D, .format = fmt, .extent = { W, H, 1 }, .mipLevels = 1,
                               .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
                               .usage = usage, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
    VkImage img;
    VkResult cr = vkCreateImage(dev, &imci, NULL, &img);
    printf("vkCreateImage(LINEAR, SPARSE_BINDING): %d\n", cr);
    if (cr != VK_SUCCESS) {
        return 0;
    }
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(dev, img, &mr);
    VkImageSubresource sub = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0 };
    VkSubresourceLayout lay;
    vkGetImageSubresourceLayout(dev, img, &sub, &lay);
    printf("image: size %llu, alignment (block) %llu, types %#x; layout offset %llu rowPitch %llu (a row of pixels %u)\n",
           (unsigned long long)mr.size, (unsigned long long)mr.alignment, mr.memoryTypeBits,
           (unsigned long long)lay.offset, (unsigned long long)lay.rowPitch, W * bpp);
    /* one host import, as the guest's RAM would be: 64 MiB, the image's pages scattered over it, out of order */
    size_t big = 64 << 20, page = 16384;
    uint8_t *host = mmap(NULL, big, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    memset(host, 0x5a, big);
    PFN_vkGetMemoryHostPointerPropertiesEXT hp = (PFN_vkGetMemoryHostPointerPropertiesEXT)
        vkGetDeviceProcAddr(dev, "vkGetMemoryHostPointerPropertiesEXT");
    VkMemoryHostPointerPropertiesEXT hpp = { .sType = VK_STRUCTURE_TYPE_MEMORY_HOST_POINTER_PROPERTIES_EXT };
    CHECK(hp(dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, host, &hpp));
    uint32_t type = 0;
    while (type < 32 && !((hpp.memoryTypeBits & mr.memoryTypeBits) & (1u << type))) type++;
    if (type == 32) {
        printf("no memory type both the sparse image and a host import take (image %#x, host %#x)\n",
               mr.memoryTypeBits, hpp.memoryTypeBits);
        return 0;
    }
    VkImportMemoryHostPointerInfoEXT imp = { .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT,
                                             .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT,
                                             .pHostPointer = host };
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &imp,
                                 .allocationSize = big, .memoryTypeIndex = type };
    VkDeviceMemory mem;
    CHECK(vkAllocateMemory(dev, &mai, NULL, &mem));
    uint64_t total = (mr.size + page - 1) / page;
    uint64_t slots = big / page;
    if (total > slots) {
        printf("image too big for the test import\n");
        return 1;
    }
    /* image piece i (16 KiB of its linear bytes) at import slot perm[i]: a scattered permutation */
    uint64_t *perm = calloc(total, 8);
    for (uint64_t i = 0; i < total; i++) {
        perm[i] = (i * 2654435761u + 977) % slots;
    }
    /* no slot twice */
    uint8_t *used = calloc(slots, 1);
    for (uint64_t i = 0; i < total; i++) {
        while (used[perm[i]]) perm[i] = (perm[i] + 1) % slots;
        used[perm[i]] = 1;
    }
    VkSparseMemoryBind *binds = calloc(total, sizeof(*binds));
    for (uint64_t i = 0; i < total; i++) {
        binds[i] = (VkSparseMemoryBind){ .resourceOffset = i * page,
                                         .size = (i + 1) * page <= mr.size ? page : mr.size - i * page,
                                         .memory = mem, .memoryOffset = perm[i] * page };
    }
    VkSparseImageOpaqueMemoryBindInfo obi = { .image = img, .bindCount = (uint32_t)total, .pBinds = binds };
    VkBindSparseInfo bsi = { .sType = VK_STRUCTURE_TYPE_BIND_SPARSE_INFO, .imageOpaqueBindCount = 1,
                             .pImageOpaqueBinds = &obi };
    VkFenceCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VkFence f;
    vkCreateFence(dev, &fci, NULL, &f);
    VkResult br = vkQueueBindSparse(q, 1, &bsi, f);
    printf("bind %llu pieces of 16 KiB, scattered: %d\n", (unsigned long long)total, br);
    if (br != VK_SUCCESS) {
        return 0;
    }
    vkWaitForFences(dev, 1, &f, VK_TRUE, 5000000000ull);
    /* the GPU writes a pattern into the image: a staging buffer of pixel values, copied in */
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    VkBufferCreateInfo sbi = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = (VkDeviceSize)W * H * bpp,
                               .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT };
    VkBuffer sb;
    CHECK(vkCreateBuffer(dev, &sbi, NULL, &sb));
    VkMemoryRequirements sr;
    vkGetBufferMemoryRequirements(dev, sb, &sr);
    uint32_t ht = 0;
    while (ht < mp.memoryTypeCount && !((sr.memoryTypeBits & (1u << ht)) &&
           (mp.memoryTypes[ht].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
           (mp.memoryTypes[ht].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))) ht++;
    VkMemoryAllocateInfo sai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = sr.size,
                                 .memoryTypeIndex = ht };
    VkDeviceMemory sm;
    CHECK(vkAllocateMemory(dev, &sai, NULL, &sm));
    CHECK(vkBindBufferMemory(dev, sb, sm, 0));
    uint32_t *px;
    CHECK(vkMapMemory(dev, sm, 0, sbi.size, 0, (void **)&px));
    for (uint64_t i = 0; i < (uint64_t)W * H * bpp / 4; i++) {
        px[i] = (uint32_t)(i * 2654435761u) ^ 0x13572468u;
    }
    VkCommandPoolCreateInfo cpi = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = fam };
    VkCommandPool cp;
    CHECK(vkCreateCommandPool(dev, &cpi, NULL, &cp));
    VkCommandBufferAllocateInfo cai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = cp,
                                        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1 };
    VkCommandBuffer cb;
    CHECK(vkAllocateCommandBuffers(dev, &cai, &cb));
    VkCommandBufferBeginInfo cbi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    vkBeginCommandBuffer(cb, &cbi);
    VkImageMemoryBarrier b1 = { .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                                .image = img, .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b1);
    VkBufferImageCopy bic = { .imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, .imageExtent = { W, H, 1 } };
    vkCmdCopyBufferToImage(cb, sb, img, VK_IMAGE_LAYOUT_GENERAL, 1, &bic);
    VkMemoryBarrier hb = { .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                           .dstAccessMask = VK_ACCESS_HOST_READ_BIT };
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &hb, 0, NULL, 0, NULL);
    vkEndCommandBuffer(cb);
    VkSubmitInfo si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cb };
    vkResetFences(dev, 1, &f);
    CHECK(vkQueueSubmit(q, 1, &si, f));
    CHECK(vkWaitForFences(dev, 1, &f, VK_TRUE, 5000000000ull));
    /* every pixel where the linear layout puts it, in the piece its offset was bound to */
    uint64_t bad = 0, checked = 0;
    for (uint32_t y = 0; y < H; y++) {
        for (uint32_t x = 0; x < W * bpp / 4; x++) {
            uint64_t off = lay.offset + (uint64_t)y * lay.rowPitch + (uint64_t)x * 4;
            uint32_t got = *(uint32_t *)(host + perm[off / page] * page + off % page);
            uint32_t want = px[(uint64_t)y * W * bpp / 4 + x];
            checked++;
            if (got != want && bad++ < 4) {
                printf("pixel word %u,%u: host has %#x, want %#x\n", x, y, got, want);
            }
        }
    }
    printf("GPU copy into the sparse linear image: %llu of %llu words wrong\n", (unsigned long long)bad,
           (unsigned long long)checked);
    return 0;
}
